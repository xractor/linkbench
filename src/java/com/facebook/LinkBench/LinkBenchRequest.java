package com.facebook.LinkBench;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import com.facebook.LinkBench.RealDistribution.DistributionType;
import com.facebook.LinkBench.distributions.AccessDistributions;
import com.facebook.LinkBench.distributions.AccessDistributions.AccessDistribution;
import com.facebook.LinkBench.distributions.ID2Chooser;
import com.facebook.LinkBench.distributions.ProbabilityDistribution;
import com.facebook.LinkBench.generators.UniformDataGenerator;
import com.facebook.LinkBench.stats.LatencyStats;
import com.facebook.LinkBench.stats.SampledStats;
import com.facebook.LinkBench.util.ClassLoadUtil;


public class LinkBenchRequest implements Runnable {
  private final Logger logger = Logger.getLogger(ConfigUtil.LINKBENCH_LOGGER);
  Properties props;
  LinkStore linkStore;
  NodeStore nodeStore;
  
  RequestProgress progressTracker;

  long nrequests;
  /** Requests per second: <= 0 for unlimited rate */
  private long requestrate;
  
  /** Maximum number of failed requests: < 0 for unlimited */
  private long maxFailedRequests;
  
  long maxtime;
  int nrequesters;
  int requesterID;
  long maxid1;
  long startid1;
  int datasize;
  Level debuglevel;
  long progressfreq_ms;
  String dbid;
  boolean singleAssoc = false;

  // cummulative percentages
  double pc_addlink;
  double pc_deletelink;
  double pc_updatelink;
  double pc_countlink;
  double pc_getlink;
  double pc_getlinklist;
  double pc_addnode;
  double pc_deletenode;
  double pc_updatenode;
  double pc_getnode;
  
  // Chance of doing historical range query
  double p_historical_getlinklist;
  
  // Cache of last link in lists where full list wasn't retrieved
  ArrayList<Link> listTailHistory;
  // Limit of cache size
  private int listTailHistoryLimit;
  
  // Probability distribution for ids in multiget
  ProbabilityDistribution multigetDist;
  
  SampledStats stats;
  LatencyStats latencyStats;

  long numfound;
  long numnotfound;

  /** 
   * Random number generator use for generating workload.  If
   * initialized with same seed, should generate same sequence of requests
   * so that tests and benchmarks are repeatable.  
   */
  Random rng;
  
  // Last node id accessed
  long lastNodeId;
  
  long requestsdone = 0;
  long errors = 0;
  boolean aborted;

  // Access distributions
  private AccessDistribution writeDist; // link writes
  private AccessDistribution readDist; // link reads
  private AccessDistribution nodeAccessDist; // node reads and writes
  
  private ID2Chooser id2chooser;
  
  public LinkBenchRequest(LinkStore linkStore,
                          NodeStore nodeStore,
                          Properties props,
                          LatencyStats latencyStats,
                          PrintStream csvStreamOut,
                          RequestProgress progressTracker,
                          Random rng,
                          int requesterID,
                          int nrequesters) {
    assert(linkStore != null);
    
    this.linkStore = linkStore;
    this.nodeStore = nodeStore;
    this.props = props;
    this.latencyStats = latencyStats;
    this.progressTracker = progressTracker;
    this.rng = rng;

    this.nrequesters = nrequesters;
    this.requesterID = requesterID;
    if (requesterID < 0 ||  requesterID >= nrequesters) {
      throw new IllegalArgumentException("Bad requester id " 
          + requesterID + "/" + nrequesters);
    }
    
    nrequests = Long.parseLong(props.getProperty(Config.NUM_REQUESTS));
    requestrate = Long.parseLong(props.getProperty(Config.REQUEST_RATE, "0"));
   
    maxFailedRequests = Long.parseLong(props.getProperty(
                                                Config.MAX_FAILED_REQUESTS, "0"));
    
    maxtime = Long.parseLong(props.getProperty(Config.MAX_TIME));
    maxid1 = Long.parseLong(props.getProperty(Config.MAX_ID));
    startid1 = Long.parseLong(props.getProperty(Config.MIN_ID));

    // math functions may cause problems for id1 = 0. Start at 1.
    if (startid1 <= 0) {
      startid1 = 1;
    }

    // is this a single assoc test?
    if (startid1 + 1 == maxid1) {
      singleAssoc = true;
      logger.info("Testing single row assoc read.");
    }

    datasize = Integer.parseInt(props.getProperty(Config.LINK_DATASIZE));
    debuglevel = ConfigUtil.getDebugLevel(props);
    dbid = props.getProperty(Config.DBID);

    pc_addlink = Double.parseDouble(props.getProperty(Config.PR_ADD_LINK));
    pc_deletelink = pc_addlink + Double.parseDouble(props.getProperty(Config.PR_DELETE_LINK));
    pc_updatelink = pc_deletelink + Double.parseDouble(props.getProperty(Config.PR_UPDATE_LINK));
    pc_countlink = pc_updatelink + Double.parseDouble(props.getProperty(Config.PR_COUNT_LINKS));
    pc_getlink = pc_countlink + Double.parseDouble(props.getProperty(Config.PR_GET_LINK));
    pc_getlinklist = pc_getlink + Double.parseDouble(props.getProperty(Config.PR_GET_LINK_LIST));
    
    pc_addnode = pc_getlinklist + Double.parseDouble(props.getProperty(Config.PR_ADD_NODE, "0.0"));
    pc_updatenode = pc_addnode + Double.parseDouble(props.getProperty(Config.PR_UPDATE_NODE, "0.0"));
    pc_deletenode = pc_updatenode + Double.parseDouble(props.getProperty(Config.PR_DELETE_NODE, "0.0"));
    pc_getnode = pc_deletenode + Double.parseDouble(props.getProperty(Config.PR_GET_NODE, "0.0"));
    if (pc_getnode > pc_getlinklist && nodeStore == null) {
      throw new IllegalArgumentException("nodeStore not provided but non-zero " +
      		                               "probability of node operation");
    }
    
    if (Math.abs(pc_getnode - 100.0) > 1e-5) {//compare real numbers
      throw new LinkBenchConfigError("Percentages of request types do not " + 
                  "add to 100, only " + pc_getnode + "!");
    }
    
    writeDist = AccessDistributions.loadAccessDistribution(props, 
            startid1, maxid1, DistributionType.WRITES);
    readDist = AccessDistributions.loadAccessDistribution(props, 
        startid1, maxid1, DistributionType.READS);

    try {
      nodeAccessDist  = AccessDistributions.loadAccessDistribution(props, 
        startid1, maxid1, DistributionType.NODE_ACCESSES);
    } catch (LinkBenchConfigError e) {
      // Not defined
      logger.info("Node access distribution not configured");
      if (pc_getnode > pc_getlinklist) {
        throw new LinkBenchConfigError("Node access distribution not " +
        		"configured but node operations have non-zero probability");
      }
      nodeAccessDist = null;
    }
    
    id2chooser = new ID2Chooser(props, startid1, maxid1, 
                                nrequesters, requesterID);

    // Distribution of #id2s per multiget, based on empirical
    // results.  TODO: make configurable
    String multigetDistClass = props.getProperty(Config.LINK_MULTIGET_DIST);
    if (multigetDistClass != null && multigetDistClass.trim().length() != 0) {
      int multigetMin = Integer.parseInt(props.getProperty(
                                            Config.LINK_MULTIGET_DIST_MIN));
      int multigetMax = Integer.parseInt(props.getProperty(
                                            Config.LINK_MULTIGET_DIST_MAX));
      try {
        multigetDist = ClassLoadUtil.newInstance(multigetDistClass,
                                            ProbabilityDistribution.class);
        multigetDist.init(multigetMin, multigetMax, props, 
                                             Config.LINK_MULTIGET_DIST_PREFIX);
      } catch (ClassNotFoundException e) {
        logger.error(e);
        throw new LinkBenchConfigError("Class" + multigetDistClass + 
            " could not be loaded as ProbabilityDistribution");
      }
    } else {
      multigetDist = null;
    }
    
    numfound = 0;
    numnotfound = 0;

    long displayfreq = Long.parseLong(props.getProperty(Config.DISPLAY_FREQ));
    String progressfreq = props.getProperty(Config.PROGRESS_FREQ);
    if (progressfreq == null) {
      progressfreq_ms = 6000L;
    } else {
      progressfreq_ms = Long.parseLong(progressfreq) * 1000L;
    }
    int maxsamples = Integer.parseInt(props.getProperty(Config.MAX_STAT_SAMPLES));
    stats = new SampledStats(requesterID, displayfreq, maxsamples, csvStreamOut);
   
    listTailHistoryLimit = 2048;
    listTailHistory = new ArrayList<Link>(listTailHistoryLimit);
    p_historical_getlinklist = Double.parseDouble(props.getProperty(
                    Config.PR_GETLINKLIST_HISTORY, "0.0")) / 100; 
        
    lastNodeId = startid1;
  }

  public long getRequestsDone() {
    return requestsdone;
  }
  
  public boolean didAbort() {
    return aborted;
  }

  // gets id1 for the request based on desired distribution
  private long chooseRequestID(DistributionType type,
                                        long previousId1) {
    AccessDistribution dist;
    switch (type) {
    case READS:
      dist = readDist;
      break;
    case WRITES:
      dist = writeDist;
      break;
    case NODE_ACCESSES:
      dist = nodeAccessDist;
      break;
    default:
      throw new RuntimeException("Unknown value for type: " + type);
    }
    long newid1 = dist.nextID(rng, previousId1);
    // Distribution responsible for generating number in range
    assert((newid1 >= startid1) && (newid1 < maxid1));
    if (Level.TRACE.isGreaterOrEqual(debuglevel)) {
      logger.trace("id1 generated = " + newid1 +
         " for access distribution: " + dist.getClass().getName() + ": " +
         dist.toString());
    }
    
    if (dist.getShuffler() != null) {
      // Shuffle to go from position in space ranked from most to least accessed,
      // to the real id space
      newid1 = startid1 + dist.getShuffler().permute(newid1 - startid1);
    }
    return newid1;
  }

  /**
   * Randomly choose a single request and execute it, updating
   * statistics
   * @param requestno
   * @return true if successful, false on error
   */
  private boolean onerequest(long requestno) {

    double r = rng.nextDouble() * 100.0;

    long starttime = 0;
    long endtime = 0;

    LinkBenchOp type = LinkBenchOp.UNKNOWN; // initialize to invalid value
    Link link = new Link();
    try {

      if (r <= pc_addlink) {
        // generate add request
        type = LinkBenchOp.ADD_LINK;
        link.id1 = chooseRequestID(DistributionType.WRITES, link.id1);
        starttime = System.nanoTime();
        addLink(link);
        endtime = System.nanoTime();
      } else if (r <= pc_deletelink) {
        type = LinkBenchOp.DELETE_LINK;
        link.id1 = chooseRequestID(DistributionType.WRITES, link.id1);
        starttime = System.nanoTime();
        deleteLink(link);
        endtime = System.nanoTime();
      } else if (r <= pc_updatelink) {
        type = LinkBenchOp.UPDATE_LINK;
        link.id1 = chooseRequestID(DistributionType.WRITES, link.id1);
        starttime = System.nanoTime();
        updateLink(link);
        endtime = System.nanoTime();
      } else if (r <= pc_countlink) {

        type = LinkBenchOp.COUNT_LINK;

        link.id1 = chooseRequestID(DistributionType.READS, link.id1);
        starttime = System.nanoTime();
        countLinks(link);
        endtime = System.nanoTime();

      } else if (r <= pc_getlink) {

        type = LinkBenchOp.MULTIGET_LINK;

        link.id1 = chooseRequestID(DistributionType.READS, link.id1);
        int nid2s = 1;
        if (multigetDist != null) { 
          nid2s = (int)multigetDist.choose(rng);
        }
        long id2s[] = new long[nid2s];
        for (int i = 0; i < nid2s; i++) {
          id2s[i] = id2chooser.chooseForOp(rng, link.id1, 0.5); 
        } 

        starttime = System.nanoTime();
        int found = getLink(link.id1, link.link_type, id2s);
        assert(found >= 0 && found <= nid2s);
        endtime = System.nanoTime();

        if (found > 0) {
          numfound += found;
        } else {
          numnotfound += nid2s - found;
        }

      } else if (r <= pc_getlinklist) {

        type = LinkBenchOp.GET_LINKS_LIST;
        Link links[];
        
        if (rng.nextDouble() < p_historical_getlinklist &&
                    !this.listTailHistory.isEmpty()) {
          links = getLinkListTail();
        } else {
          link.id1 = chooseRequestID(DistributionType.READS, link.id1);
          starttime = System.nanoTime();
          links = getLinkList(link);
          endtime = System.nanoTime();
        }
        
        int count = ((links == null) ? 0 : links.length);
        stats.addStats(LinkBenchOp.RANGE_SIZE, count, false);
        if (Level.TRACE.isGreaterOrEqual(debuglevel)) {
          logger.trace("getlinklist count = " + count);
        }
      } else if (r <= pc_addnode) {
        type = LinkBenchOp.ADD_NODE;
        Node newNode = createNode();
        starttime = System.nanoTime();
        lastNodeId = nodeStore.addNode(dbid, newNode);
        endtime = System.nanoTime();
        if (Level.TRACE.isGreaterOrEqual(debuglevel)) {
          logger.trace("addNode " + newNode);
        }
      } else if (r <= pc_updatenode) {
        type = LinkBenchOp.UPDATE_NODE;
        // Generate new data randomly
        Node newNode = createNode();
        // Choose an id that has previously been created (but might have
        // been since deleted
        newNode.id = chooseRequestID(DistributionType.NODE_ACCESSES, 
                                     lastNodeId);
        starttime = System.nanoTime();
        boolean changed = nodeStore.updateNode(dbid, newNode);
        endtime = System.nanoTime();
        lastNodeId = newNode.id;
        if (Level.TRACE.isGreaterOrEqual(debuglevel)) {
          logger.trace("updateNode " + newNode + " changed=" + changed);
        }
      } else if (r <= pc_deletenode) {
        type = LinkBenchOp.DELETE_NODE;
        long idToDelete = chooseRequestID(DistributionType.NODE_ACCESSES, 
                                          lastNodeId);
        starttime = System.nanoTime();
        boolean deleted = nodeStore.deleteNode(dbid, LinkStore.ID1_TYPE,
                                                     idToDelete);
        endtime = System.nanoTime();
        lastNodeId = idToDelete;
        if (Level.TRACE.isGreaterOrEqual(debuglevel)) {
          logger.trace("deleteNode " + idToDelete + " deleted=" + deleted);
        }
      } else if (r <= pc_getnode) {
        type = LinkBenchOp.GET_NODE;
        starttime = System.nanoTime();
        long idToFetch = chooseRequestID(DistributionType.NODE_ACCESSES, 
                                         lastNodeId);
        Node fetched = nodeStore.getNode(dbid, LinkStore.ID1_TYPE, idToFetch);
        endtime = System.nanoTime();
        lastNodeId = idToFetch;
        if (Level.TRACE.isGreaterOrEqual(debuglevel)) {
          if (fetched == null) {
            logger.trace("getNode " + idToFetch + " not found");
          } else {
            logger.trace("getNode " + fetched);
          }
        }
      } else {
        logger.error("No-op in requester: last probability < 1.0");
        return false;
      }


      // convert to microseconds
      long timetaken = (endtime - starttime)/1000;

      // record statistics
      stats.addStats(type, timetaken, false);
      latencyStats.recordLatency(requesterID, type, timetaken);

      return true;
    } catch (Throwable e){//Catch exception if any

      long endtime2 = System.nanoTime();

      long timetaken2 = (endtime2 - starttime)/1000;

      logger.error(type.displayName() + " error " +
                         e.getMessage(), e);

      stats.addStats(type, timetaken2, true);
      linkStore.clearErrors(requesterID);
      return false;
    }


  }

  private Node createNode() {
    // TODO put in some real data
    return new Node(-1, LinkStore.ID1_TYPE, 1, 1, 
        UniformDataGenerator.gen(rng, new byte[512], (byte)0, 256));
  }

  @Override
  public void run() {
    logger.info("Requester thread #" + requesterID + " started: will do "
        + nrequests + " ops.");
    logger.debug("Requester thread #" + requesterID + " first random number "
                  + rng.nextLong());
    
    try {
      this.linkStore.initialize(props, Phase.REQUEST, requesterID);
      if (this.nodeStore != null && this.nodeStore != this.linkStore) {
        this.nodeStore.initialize(props, Phase.REQUEST, requesterID);
      }
    } catch (Exception e) {
      logger.error("Error while initializing store", e);
      throw new RuntimeException(e);
    }
    
    long starttime = System.currentTimeMillis();
    long endtime = starttime + maxtime * 1000;
    long lastupdate = starttime;
    long curtime = 0;
    long i;

    if (singleAssoc) {
      LinkBenchOp type = LinkBenchOp.UNKNOWN;
      try {
        Link link = new Link();
        // add a single assoc to the database
        link.id1 = 45;
        link.id1 = 46;
        type = LinkBenchOp.ADD_LINK;
        addLink(link);

        // read this assoc from the database over and over again
        type = LinkBenchOp.MULTIGET_LINK;
        for (i = 0; i < nrequests; i++) {
          int found = getLink(link.id1, link.link_type,
                                  new long[]{link.id2});
          if (found == 1) {
            requestsdone++;
          } else {
            logger.warn("ThreadID = " + requesterID +
                               " not found link for id1=45");
          }
        }
      } catch (Throwable e) {
        logger.error(type.displayName() + "error " +
                         e.getMessage(), e);
        aborted = true;
      }
      return;
    }
    
    int requestsSinceLastUpdate = 0;
    long reqTime_ns = System.nanoTime();
    double requestrate_ns = ((double)requestrate)/1e9;
    for (i = 0; i < nrequests; i++) {
      if (requestrate > 0) {
        reqTime_ns = Timer.waitExpInterval(rng, reqTime_ns, requestrate_ns);
      }
      boolean success = onerequest(i);
      requestsdone++;
      if (!success) {
        errors++;
        if (maxFailedRequests >= 0 && errors > maxFailedRequests) {
          logger.error(String.format("Requester #%d aborting: %d failed requests" +
          		" (out of %d total) ", requesterID, errors, requestsdone));
          aborted = true;
          return;
        }
      }
      
      curtime = System.currentTimeMillis();
      
      if (curtime > lastupdate + progressfreq_ms) {
        logger.info(String.format("Requester #%d %d/%d requests done",
            requesterID, requestsdone, nrequests));
        lastupdate = curtime;
      }
      
      requestsSinceLastUpdate++;
      if (curtime > endtime) {
        break;
      }
      if (requestsSinceLastUpdate >= RequestProgress.THREAD_REPORT_INTERVAL) {
        progressTracker.update(requestsSinceLastUpdate);
        requestsSinceLastUpdate = 0;
      }
    }
    
    progressTracker.update(requestsSinceLastUpdate);

    stats.displayStats(System.currentTimeMillis(), Arrays.asList(
        LinkBenchOp.MULTIGET_LINK, LinkBenchOp.GET_LINKS_LIST,
        LinkBenchOp.COUNT_LINK,
        LinkBenchOp.UPDATE_LINK, LinkBenchOp.ADD_LINK, 
        LinkBenchOp.RANGE_SIZE, LinkBenchOp.ADD_NODE,
        LinkBenchOp.UPDATE_NODE, LinkBenchOp.DELETE_NODE,
        LinkBenchOp.GET_NODE));
    logger.info("ThreadID = " + requesterID +
                       " total requests = " + i +
                       " requests/second = " + ((1000 * i)/(curtime - starttime)) +
                       " found = " + numfound +
                       " not found = " + numnotfound);

  }

  int getLink(long id1, long link_type, long id2s[]) throws Exception {
    Link links[] = linkStore.multigetLinks(dbid, id1, link_type, id2s);
    return links == null ? 0 : links.length;
  }

  Link[] getLinkList(Link link) throws Exception {
    Link links[] = linkStore.getLinkList(dbid, link.id1, link.link_type);
    
    // If there were more links than limit, record
    if (links != null && links.length >= linkStore.getRangeLimit()) {
      Link lastLink = links[links.length-1];
      if (Level.TRACE.isGreaterOrEqual(debuglevel)) {
        logger.trace("Maybe more history for (" + link.id1 +"," + 
                      link.link_type + " older than " + lastLink.time);
      }
      
      if (listTailHistory.size() < listTailHistoryLimit) {
        listTailHistory.add(lastLink.clone());
      } else {
        int choice = rng.nextInt(listTailHistory.size());
        listTailHistory.set(choice, lastLink.clone());
      }
    }
    return links;
  }

  Link[] getLinkListTail() throws Exception {
    assert(!listTailHistory.isEmpty());
    int choice = rng.nextInt(listTailHistory.size());
    Link prevLast = listTailHistory.get(choice);
    
    // Get links past the oldest last retrieved
    Link links[] = linkStore.getLinkList(dbid, prevLast.id1,
        prevLast.link_type, 0, prevLast.time, 1, linkStore.getRangeLimit());
    
    if (Level.TRACE.isGreaterOrEqual(debuglevel)) {
      logger.trace("Historical range query for (" + prevLast.id1 +"," +
                    prevLast.link_type + " older than " + prevLast.time +
                    ": " + (links == null ? 0 : links.length) + " results");
    }
    
    // There might be yet more history
    if (links != null && links.length == linkStore.getRangeLimit()) {
      Link last = links[links.length-1];
      if (Level.TRACE.isGreaterOrEqual(debuglevel)) {
        logger.trace("might be yet more history for (" + last.id1 +"," +
                      last.link_type + " older than " + last.time);
      }
      // Update in place
      listTailHistory.set(choice, last.clone());
    }
      
    return links;
  }

  long countLinks(Link link) throws Exception {
    return linkStore.countLinks(dbid, link.id1, link.link_type);
  }

  void addLink(Link link) throws Exception {
    link.link_type = LinkStore.LINK_TYPE;
    link.id1_type = LinkStore.ID1_TYPE;
    link.id2_type = LinkStore.ID2_TYPE;

    link.id2 = id2chooser.chooseForOp(rng, link.id1, 0.5);

    link.visibility = LinkStore.VISIBILITY_DEFAULT;
    link.version = 0;
    link.time = System.currentTimeMillis();

    // generate data as a sequence of random characters from 'a' to 'd'
    link.data = UniformDataGenerator.gen(rng, new byte[datasize],
                                          (byte)'a', 4);

    // no inverses for now
    linkStore.addLink(dbid, link, true);

  }

  void updateLink(Link link) throws Exception {
    link.link_type = LinkStore.LINK_TYPE;
    
    // Update one of the existing links
    link.id2 = id2chooser.chooseForOp(rng, link.id1, 0.5);

    link.id1_type = LinkStore.ID1_TYPE;
    link.id2_type = LinkStore.ID2_TYPE;
    link.visibility = LinkStore.VISIBILITY_DEFAULT;
    link.version = 0;
    link.time = System.currentTimeMillis();

    // generate data as a sequence of random characters from 'e' to 'h'
    link.data = UniformDataGenerator.gen(rng, new byte[datasize],
                                               (byte)'e', 4);
   

    // no inverses for now
    linkStore.addLink(dbid, link, true);

  }

  void deleteLink(Link link) throws Exception {
    link.link_type = LinkStore.LINK_TYPE;
    link.id2 = id2chooser.chooseForOp(rng, link.id1, 1.0);

    // no inverses for now
    linkStore.deleteLink(dbid, link.id1, link.link_type, link.id2,
                     true, // no inverse
                     false);// let us hide rather than delete
  }

  public static class RequestProgress {
    // How many ops before a thread should register its progress
    static final int THREAD_REPORT_INTERVAL = 250;
    // How many ops before a progress update should be printed to console
    private static final int PROGRESS_PRINT_INTERVAL = 10000;
    
    private final Logger progressLogger;
    
    private long totalRequests;
    private final AtomicLong requestsDone;
    
    private long startTime;
    private long timeLimit_s;

    public RequestProgress(Logger progressLogger,
                      long totalRequests, long timeLimit) {
      this.progressLogger = progressLogger;
      this.totalRequests = totalRequests;
      this.requestsDone = new AtomicLong();
      this.timeLimit_s = timeLimit;
      this.startTime = 0;
    }
    
    public void startTimer() {
      startTime = System.currentTimeMillis();
    }
    
    public void update(long requestIncr) {
      long curr = requestsDone.addAndGet(requestIncr);
      long prev = curr - requestIncr;
      
      long interval = PROGRESS_PRINT_INTERVAL;
      if ((curr / interval) > (prev / interval) || curr == totalRequests) {
        float progressPercent = ((float) curr) / totalRequests * 100;
        long now = System.currentTimeMillis();
        long elapsed = now - startTime;
        float elapsed_s = ((float) elapsed) / 1000;
        float limitPercent = (elapsed_s / ((float) timeLimit_s)) * 100;
        float rate = curr / ((float)elapsed_s);
        progressLogger.info(String.format(
            "%d/%d requests finished: %.1f%% complete at %.1f ops/sec" +
            " %.1f/%d secs elapsed: %.1f%% of time limit used",
            curr, totalRequests, progressPercent, rate,
            elapsed_s, timeLimit_s, limitPercent));
            
      }
    }
  }

  public static RequestProgress createProgress(Logger logger,
       Properties props) {
    long total_requests = Long.parseLong(props.getProperty(Config.NUM_REQUESTS))
                      * Long.parseLong(props.getProperty(Config.NUM_REQUESTERS));
    return new RequestProgress(logger, total_requests,
        Long.parseLong(props.getProperty(Config.MAX_TIME)));
  }
}

