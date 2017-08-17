package me.lcw.sc2api;

import java.io.File;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.threadly.concurrent.PriorityScheduler;
import org.threadly.litesockets.ThreadedSocketExecuter;
import org.threadly.util.Clock;

import com.google.protobuf.util.JsonFormat;

import SC2APIProtocol.Raw.Unit;
import SC2APIProtocol.Sc2Api.Difficulty;
import SC2APIProtocol.Sc2Api.InterfaceOptions;
import SC2APIProtocol.Sc2Api.LocalMap;
import SC2APIProtocol.Sc2Api.PlayerSetup;
import SC2APIProtocol.Sc2Api.PlayerType;
import SC2APIProtocol.Sc2Api.Race;
import SC2APIProtocol.Sc2Api.Request;
import SC2APIProtocol.Sc2Api.RequestCreateGame;
import SC2APIProtocol.Sc2Api.RequestGameInfo;
import SC2APIProtocol.Sc2Api.RequestJoinGame;
import SC2APIProtocol.Sc2Api.RequestObservation;
import SC2APIProtocol.Sc2Api.RequestSaveReplay;
import SC2APIProtocol.Sc2Api.Response;
import SC2APIProtocol.Sc2Api.Status;

public class SyncExample1 {
  
  public static void printHelp() {
    System.out.println("usage:");
    System.out.println("java -jar sc2-java-api <mapPath> <port>");
    System.out.println("example:");
    System.out.println("java -jar sc2-java-api  /home/user/StarCraftII/Maps/Melee/Simple64.SC2Map 5000");
    System.out.println("");
  }


  public static void main(String[] args) throws Exception {
    //configureLogging
    System.setProperty(org.slf4j.impl.SimpleLogger.LOG_FILE_KEY, "System.out");
    System.setProperty(org.slf4j.impl.SimpleLogger.DEFAULT_LOG_LEVEL_KEY, "TRACE");
    System.setProperty(org.slf4j.impl.SimpleLogger.SHOW_THREAD_NAME_KEY, "false");
    System.setProperty(org.slf4j.impl.SimpleLogger.SHOW_DATE_TIME_KEY, "true");
    System.setProperty(org.slf4j.impl.SimpleLogger.DATE_TIME_FORMAT_KEY, "yyyy-MM-dd HH:mm:ss.S");
    
    Logger log = LoggerFactory.getLogger(SyncExample1.class);
    
    //Read arguments
    String mapPath = null;
    int gamePort = -1;
    try {
      if(args.length == 2) {
        mapPath = args[0];
        gamePort = Integer.parseInt(args[1]);
      }
    } catch(Exception e) {
      log.error("Error parsing arguments", e);
    }
    
    if(mapPath == null || gamePort == -1) {
      printHelp();
      System.exit(1);
    }
    


    //Setup ThreadPool and MultiThreaded SocketExecuter
    PriorityScheduler PS = new PriorityScheduler(10);
    ThreadedSocketExecuter TSE = new ThreadedSocketExecuter(PS);
    TSE.start();

    //Connect client to SC2
    BaseSC2Client client = new BaseSC2Client(PS, TSE, new InetSocketAddress("localhost", gamePort));
    client.connect().get(10, TimeUnit.SECONDS);

    //Create a new Game
    RequestCreateGame cg = RequestCreateGame.newBuilder()
        .setLocalMap(LocalMap.newBuilder().setMapPath(mapPath))
        .addPlayerSetup(PlayerSetup.newBuilder().setType(PlayerType.Participant).setRace(Race.Zerg))
        .addPlayerSetup(PlayerSetup.newBuilder().setType(PlayerType.Computer).setRace(Race.Terran).setDifficulty(Difficulty.Easy))
        .build();
    Request req = Request.newBuilder().setCreateGame(cg).build();
    Response resp = client.sendRawRequest(req).get(10, TimeUnit.SECONDS); //Send the request and wait for a response
    if(!resp.hasCreateGame() || !resp.hasStatus() || resp.getStatus() != Status.init_game) {
      log.error("Got unexpected Response! {}", JsonFormat.printer().omittingInsignificantWhitespace().print(resp));
      System.exit(1);      
    }

    //Create Join Game
    RequestJoinGame jg = RequestJoinGame.newBuilder()
        .setRace(Race.Zerg)
        .setOptions(InterfaceOptions.newBuilder().setRaw(true).setScore(false))
        .build();
    
    req = Request.newBuilder().setJoinGame(jg).build();

    resp = client.sendRawRequest(req).get(10, TimeUnit.SECONDS);
    if(!resp.hasJoinGame() || !resp.hasStatus() || resp.getStatus() != Status.in_game) {
      log.error("Got unexpected Response! {}", JsonFormat.printer().omittingInsignificantWhitespace().print(resp));
      System.exit(1); 
    }
   
    //Get Game info
    Response GI = client.sendRawRequest(Request.newBuilder().setGameInfo(RequestGameInfo.newBuilder()).build()).get();

    //Step a few times to get things running.
    resp = client.step().get(10, TimeUnit.SECONDS);  
    resp = client.step().get(10, TimeUnit.SECONDS);  
    resp = client.step().get(10, TimeUnit.SECONDS);  
    
    //Get Observation to find our base
    Response OR = client.sendRawRequest(Request.newBuilder().setObservation(RequestObservation.newBuilder()).build()).get(10, TimeUnit.SECONDS);
    List<Unit> units = OR.getObservation().getObservation().getRawData().getUnitsList();
    Unit base = null;
    for(Unit u: units) {
      if(u.getUnitType() == 86) {
        base = u;
        break;
      }
    }
    log.info("BaseInfo:{}", JsonFormat.printer().omittingInsignificantWhitespace().print(base));
    
    //Step through the rest of the game
    resp = null;
    while(resp == null || (resp.hasStatus() && resp.getStatus() != Status.ended) ) {
      resp = client.step().get();  
    }
    
    //On Game end get replay and save it.
    resp = client.sendRawRequest(Request.newBuilder().setSaveReplay(RequestSaveReplay.newBuilder()).build()).get(10, TimeUnit.SECONDS);
    File tempFile = File.createTempFile(System.currentTimeMillis()+"-", ".SC2Replay");
    RandomAccessFile raf = new RandomAccessFile(tempFile, "rw");
    raf.write(resp.getSaveReplay().getData().toByteArray());
    raf.close();
    log.info("Saved replay to: {}", tempFile.getAbsolutePath());

  }
}