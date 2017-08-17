package me.lcw.sc2api;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.text.ParseException;
import java.util.Base64;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.threadly.concurrent.PriorityScheduler;
import org.threadly.concurrent.future.FutureCallback;
import org.threadly.concurrent.future.ListenableFuture;
import org.threadly.concurrent.future.SettableListenableFuture;
import org.threadly.litesockets.SocketExecuter;
import org.threadly.litesockets.TCPClient;
import org.threadly.litesockets.buffers.ReuseableMergedByteBuffers;
import org.threadly.litesockets.protocols.http.request.HTTPRequest;
import org.threadly.litesockets.protocols.http.request.HTTPRequestBuilder;
import org.threadly.litesockets.protocols.http.shared.HTTPConstants;
import org.threadly.litesockets.protocols.ws.WebSocketFrameParser;
import org.threadly.litesockets.protocols.ws.WebSocketOpCode;
import org.threadly.litesockets.protocols.ws.WebSocketFrameParser.WebSocketFrame;
import org.threadly.util.StringUtils;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;

import SC2APIProtocol.Sc2Api.Request;
import SC2APIProtocol.Sc2Api.RequestStep;
import SC2APIProtocol.Sc2Api.Response;

public class BaseSC2Client {
  public static final Logger log = LoggerFactory.getLogger(BaseSC2Client.class);
  
  public static final HTTPRequest WSRequest = new HTTPRequestBuilder()
      .setHeader(HTTPConstants.HTTP_KEY_UPGRADE, "websocket")
      .setHeader(HTTPConstants.HTTP_KEY_CONNECTION, "Upgrade")
      .setHeader(HTTPConstants.HTTP_KEY_WEBSOCKET_VERSION, "13")
      .setHeader(HTTPConstants.HTTP_KEY_WEBSOCKET_KEY, Base64.getEncoder().encodeToString(StringUtils.makeRandomString(20).getBytes()))
      .setPath("/sc2api")
      .build();
  public static final Request STEP_REQUEST;
  public static final ByteBuffer WRAPPED_STEP_REQUEST;
  public static final String STEP_JSON;
  
  static {
    STEP_REQUEST = Request.newBuilder().setStep(RequestStep.newBuilder().build()).build();
    WebSocketFrame wsf = WebSocketFrameParser.makeWebSocketFrame(STEP_REQUEST.toByteArray().length, WebSocketOpCode.Binary.getValue(), false);
    ByteBuffer bb = ByteBuffer.allocate(STEP_REQUEST.toByteArray().length+wsf.getRawFrame().remaining());
    bb.put(wsf.getRawFrame());
    bb.put(STEP_REQUEST.toByteArray());
    bb.flip();
    WRAPPED_STEP_REQUEST = bb.asReadOnlyBuffer();
    try {
      STEP_JSON = JsonFormat.printer().omittingInsignificantWhitespace().print(STEP_REQUEST);
    } catch (InvalidProtocolBufferException e) {
      throw new RuntimeException(e);
    }
  }
  
  private final ReuseableMergedByteBuffers mbb = new ReuseableMergedByteBuffers();
  private final ConcurrentLinkedQueue<SettableListenableFuture<Response>> responseQueue = new ConcurrentLinkedQueue<>();
  private final PriorityScheduler ps;
  private final SocketExecuter se;
  private final InetSocketAddress host;
  private final TCPClient client;
  private volatile WebSocketFrame lastFrame = null;
  private volatile boolean connectCalled = false;
  private volatile boolean wsDone = false;

  public BaseSC2Client(PriorityScheduler ps, SocketExecuter se, InetSocketAddress host) throws IOException {
    this.ps = ps;
    this.se = se;
    this.host = host;
    this.client = se.createTCPClient(host.getHostName(), host.getPort());
    this.client.setReader((c)->onRead());
    client.write(WSRequest.getByteBuffer()).addCallback(new FutureCallback<Object>() {

      @Override
      public void handleResult(Object result) {
        log.debug("Sent WebSocket Request:{}", WSRequest);
      }

      @Override
      public void handleFailure(Throwable t) {
        
      }});
  }
  
  public synchronized ListenableFuture<Response> step() {
    SettableListenableFuture<Response> slf = new SettableListenableFuture<Response>();
    if(isConnected()) {
      log.debug("Sending Step Message:{}", STEP_JSON);
      responseQueue.add(slf);
      client.write(WRAPPED_STEP_REQUEST.duplicate());
      return slf;
    }
    slf.setFailure(new IOException("Client is not connected!"));
    return slf;
  }
  
  public synchronized ListenableFuture<Response> sendRawRequest(Request req) {
    SettableListenableFuture<Response> slf = new SettableListenableFuture<Response>();
    if(isConnected()) {
      try {
        if(log.isDebugEnabled()) {
          log.debug("Sending Message:{}",  JsonFormat.printer().omittingInsignificantWhitespace().print(req));
        }
      } catch (InvalidProtocolBufferException e) {
        throw new RuntimeException(e);
      }
      responseQueue.add(slf);
      client.write(WebSocketFrameParser.makeWebSocketFrame(req.toByteArray().length, WebSocketOpCode.Binary.getValue(), false).getRawFrame());
      client.write(ByteBuffer.wrap(req.toByteArray()));
      return slf;
    }
    slf.setFailure(new IOException("Client is not connected!"));
    return slf;
  }
  
  public ListenableFuture<?> connect() {
    connectCalled = true;
    log.info("Connecting to:{}", host);
    ListenableFuture<?> lf = client.connect();
    lf.addCallback(new FutureCallback<Object>() {

      @Override
      public void handleResult(Object result) {
        log.info("Connected to:{}", host);    
      }

      @Override
      public void handleFailure(Throwable t) {
      }
      
    });
    return lf;
  }
  
  public boolean isConnected() {
    return !client.isClosed() && connectCalled;
  }
  
  private void onRead() {
    mbb.add(client.getRead());
    while(mbb.hasRemaining()) {
      if(wsDone) {
        if(lastFrame == null) {
          try {
            lastFrame = WebSocketFrameParser.parseWebSocketFrame(mbb);
          } catch (ParseException e) {
          }
        }
        if(lastFrame != null && mbb.remaining() >= lastFrame.getPayloadDataLength()) {
          byte[] ba = new byte[(int)lastFrame.getPayloadDataLength()];
          mbb.get(ba);
          if(lastFrame.hasMask()) {
            ByteBuffer bb = lastFrame.unmaskPayload(ByteBuffer.wrap(ba));
            bb.get(ba);
          }
          try {
            Response resp = Response.parseFrom(ba);
            if(log.isDebugEnabled()) {
              log.debug("Got Response:{}", JsonFormat.printer().omittingInsignificantWhitespace().print(resp));
            }
            responseQueue.poll().setResult(resp);
          } catch (InvalidProtocolBufferException e) {
            responseQueue.poll().setFailure(e);
          }
          lastFrame = null;
        } else {
          break;
        }
      } else {
        int pos = mbb.indexOf(HTTPConstants.HTTP_DOUBLE_NEWLINE_DELIMINATOR);
        if(pos >= 0) {
          String data = mbb.getAsString(pos+4);
          log.debug("Got WS upgrade {}", data);
          wsDone = true;
        } else {
          break;
        }
      }
    }
  }
  
}
