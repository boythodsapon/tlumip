package com.pb.despair.pt.daf;

import com.pb.common.daf.Message;
import com.pb.common.daf.MessageProcessingTask;
import com.pb.common.daf.MessageFactory;
import com.pb.common.util.ObjectUtil;
import com.pb.common.util.ResourceUtil;

import com.pb.despair.pt.*;

import java.io.*;

import java.util.*;
import java.util.logging.Logger;


/**
 *
 * PTDafMaster sends messages to work queues.
 *
 *
 * @author    Steve Hansen
 * @version   1.0, 5/5/2004
 *
 */
public class PTDafMaster extends MessageProcessingTask {
    boolean debug = false;
    static final String mcPurposes = new String("wcsrob"); //work,school,shop,recreate,other,workbased
    static final int TOTALSEGMENTS = 9;
    static final int TOTALOCCUPATIONS = 8;
    static final int TOTAL_DCLOGSUMS = 63;
    static final int MAXZONENUMBER = 4141;

    static int MAXBLOCKSIZE;  //will be initialized through resource bundle as it may change depending
                                //on the scenario (ex. 5000 for pleaseWork, 8 for smallPop)

                                                               
    PTResults results;

    int[] indexArray;
    PTHousehold[] households;
    PTPerson[] persons;
    PTDataReader dataReader;
    TazData tazData;
    int totalHouseholds;
    int totalPersons;
    int totalModeChoiceLogsums = 0;
    int totalDCLogsums = 0;
    int totalWorkers = 0;
    int mcLogsumCount = 0;
    int dcLogsumCount = 0;
    int tazUpdateCount = 0;
    int personsWithWorkplaceCount = 0;
    int householdsProcessedCount = 0;
    int householdCounter = 0;
    ResourceBundle ptdafRb; //this will be read in after the scenarioName has been read in from RunParams.txt
    ResourceBundle ptRb; // this will be read in after pathToRb has been read in from RunParams.txt
    int NUMBER_OF_WORK_QUEUES;
    int NUMBER_OF_WORK_QUEUES_AGGREGATE;
    int lastWorkQueue;

    /**
     * Read parameters
     * Send out mode choice logsums to workers
     * Read households
     * Run household auto ownership model
     * Read persons
     * Set person attributes (Home TAZ, householdWorkLogsum) from household attributes
     * Sort the person array by occupation (0->8) and householdWorkLogsumMarket (0->8)
     */
    public void onStart() {
        logger.info("***" + getName() + " started");

        //We need to read in the Run Parameters (timeInterval and pathToResourceBundle) from the RunParams.txt file
        //that was written by the Application Orchestrator
        BufferedReader reader = null;
        String scenarioName = null;
        int timeInterval = -1;
        String pathToRb = null;
        try {
            logger.info("Reading RunParams.txt file");
            reader = new BufferedReader(new FileReader(new File( Scenario.runParamsFileName )));
            scenarioName = reader.readLine();
            logger.info("\tScenario Name: " + scenarioName);
            timeInterval = Integer.parseInt(reader.readLine());
            logger.info("\tTime Interval: " + timeInterval);
            pathToRb = reader.readLine();
            logger.info("\tResourceBundle Path: " + pathToRb);
        } catch (Exception e) {
            e.printStackTrace();
        }
        //Get properties files and set class attributes based on those properties.
            //First get the ptdaf.properties which will be in the classpath and
            //set the number of work queues
        ptdafRb = ResourceUtil.getResourceBundle("ptdaf_"+scenarioName);
        NUMBER_OF_WORK_QUEUES = Integer.parseInt(ResourceUtil.getProperty(ptdafRb, "workQueues"));
        NUMBER_OF_WORK_QUEUES_AGGREGATE = Integer.parseInt(ResourceUtil.getProperty(ptdafRb, "workQueuesAggregate"));
        lastWorkQueue = NUMBER_OF_WORK_QUEUES_AGGREGATE;
            //Next get pt.properties and set max block size.
        ptRb = ResourceUtil.getPropertyBundle(new File(pathToRb));
        MAXBLOCKSIZE = Integer.parseInt(ResourceUtil.getProperty(ptRb,"max.block.size"));

        //Start mode choice logsums.  The workers will decide if the MC Logsums
        //actually need to be recalculated based on a class boolean.  If they
        //don't need to be calculated the workers will send back an empty message.
        //Either way, the Master will be able to go to the next model based
        //on the total "MCLogsumsCreated" messages coming back.
        logger.info("Starting the Mode Choice Logsum calculations");
        startMCLogsums();
        
        //Read the SynPop data
        dataReader = new PTDataReader(ptRb);
        logger.info("Adding synthetic population from database");
        households = dataReader.readHouseholds("households.file");
        totalHouseholds = households.length;
        logger.info("Total Number of HHs :" + totalHouseholds);
        if(debug) logger.fine("Size of households: " + ObjectUtil.sizeOf(households));

        logger.info("Reading the Persons file");
        persons = dataReader.readPersons("persons.file");
        if(debug) logger.fine("Size of persons: " + ObjectUtil.sizeOf(persons));
        totalPersons = persons.length;

        //add worker info to Households and add homeTAZ and hh work segment to persons
        //This method sorts households and persons by ID.  Leaves arrays in sorted positions.
        dataReader.addPersonInfoToHouseholdsAndHouseholdInfoToPersons(households,
                persons);

        //Persons must be read in and .addPersonInfo... must be called before running
        //auto ownership, otherwise PTHousehold.workers will be 0 and
        //the work segment will be calculated incorrectly.
        logger.info("Starting the AutoOwnershipModel");
        households = dataReader.runAutoOwnershipModel(households);

        PTSummarizer.summarizeHouseholds(households,ResourceUtil.getProperty(ptRb,"hhSummary.file"));
        PTSummarizer.summarizePersons(persons,ResourceUtil.getProperty(ptRb,"personSummary.file"));

        logger.info("Finished onStart()");
    }

    /**
     * Wait for message.  
     * If message is MC_LOGSUMS_CREATED, startWorkplaceLocation
     * If message is WORKPLACE_LOCATIONS_CALCULATED, add the 
     *    workers to the persons array, and check if done with all segments.  
     *    If done,
     *       Set TazDataArrays, which will update the zone data in each
     *         node with the number of households and teachers in each TAZ.
     *       Add persons with workplace locations to households.
     *       Sort the household array by worklogsum segment and non-worklogsum segment.
     * If message is TAZDATA_UPDATED, check if all nodes have completed updating their data
     *    If done, startDCLogsums()
     * If message is DC_LOGSUMS_CREATED, check if all segments have been completed.
     *    If done, startProcessHouseholds(): Send out initial blocks of households to workers.
     * If message is HOUSEHOLDS_PROCESSED
     *    Send households to householdResults method, which will increment up householdsProcessedCount
     *       and send households for writing to results file.
     *    If households processed less than total households, sendMoreHouseholds()
     *   
     *    
     */
    public void onMessage(Message msg) {
        logger.info(getName() + " received messageId=" + msg.getId() +
            " message from=" + msg.getSender() + " @time=" + new Date());

        if (msg.getId().equals(MessageID.MC_LOGSUMS_CREATED)) {
            mcLogsumCount++;
            logger.fine("mcLogsumCount: " + mcLogsumCount);

            if (mcLogsumCount == totalModeChoiceLogsums) {
                logger.info("ModeChoice Logsums completed.");
                createWorkplaceLocationMessages();
            }

        } else if (msg.getId().equals(MessageID.WORKPLACE_LOCATIONS_CALCULATED)) {
            addToPersonsArray(msg);
            logger.info("Persons with workplace location: " +
                personsWithWorkplaceCount);

            //if the persons in the person array is equal to the total number of persons,
            //we are done with the workplace location model
            if (personsWithWorkplaceCount == totalPersons) {
                setTazDataArrays();
                households = dataReader.addPersonsToHouseholds(households,persons);
//                Arrays.sort(households);  //moved to the beginning of the HH processing method.

            }

        } else if (msg.getId().equals(MessageID.TAZDATA_UPDATED)) {

            tazUpdateCount++;
            logger.fine("tazUpdateCount: " + tazUpdateCount);

            if (tazUpdateCount == NUMBER_OF_WORK_QUEUES) {
                logger.info("Taz data has been updated on all workers.");
                startDCLogsums();
            }

        } else if (msg.getId().equals(MessageID.DC_LOGSUMS_CREATED) ||
                msg.getId().equals(MessageID.DC_EXPUTILITIES_CREATED)) {

            dcLogsumCount++;
            if(debug) logger.info("dcLogsumCount: " + dcLogsumCount);

            if (dcLogsumCount == TOTAL_DCLOGSUMS) {
                logger.info("Destination Choice Logsums completed.");
                startProcessHouseholds();
            }

        } else if (msg.getId().equals(MessageID.HOUSEHOLDS_PROCESSED)) {
            householdsProcessedCount = householdsProcessedCount + ((Integer)msg.getValue("nHHs")).intValue();
            logger.info("Households processed so far: " + householdsProcessedCount);

            if ((((Integer) msg.getValue("sendMore")).intValue() == 1) &&
                    (householdCounter < totalHouseholds)) {
                sendMoreHouseholds(msg);
            }

            if (householdsProcessedCount == totalHouseholds) {
                Message allDone = createMessage();
                allDone.setId(MessageID.ALL_HOUSEHOLDS_PROCESSED);
                sendTo("ResultsWriterQueue",allDone);
            }

        } else if(msg.getId().equals(MessageID.ALL_FILES_WRITTEN)){
            logger.info("Signaling to the File Monitor that the model is finished");
            File doneFile = new File(ResourceUtil.getProperty(ptRb,"done.file"));

            try {
                PrintWriter writer = new PrintWriter(new FileWriter(
                            doneFile));
                writer.println("pt daf is done." + new Date());
                writer.close();
                logger.info("pt daf is done.");
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * startMCLogsums - sends messages to the work queues to create MC Logsum matrices
     *
     */
    private void startMCLogsums() {
        logger.info("Creating tour mode choice logsums");

        //enter loop on purposes
        for (int purpose = 0; purpose < mcPurposes.length(); ++purpose) {
            char thisPurpose = mcPurposes.charAt(purpose);

            //enter loop on segments
            for (int segment = 0; segment < TOTALSEGMENTS; ++segment) {
                Message mcLogsumMessage = createMessage();
                mcLogsumMessage.setId(MessageID.CREATE_MC_LOGSUMS);
                mcLogsumMessage.setValue("purpose",
                    new Character(mcPurposes.charAt(purpose)));
                mcLogsumMessage.setValue("segment", new Integer(segment));

                String queueName = getQueueName2();
                mcLogsumMessage.setValue("queue", queueName);
                logger.info("Sending MC logsums to " + queueName + " : " +
                    thisPurpose + segment);
                sendTo(queueName, mcLogsumMessage);
                totalModeChoiceLogsums++;
            }
        }
    }

    /**
     * createWorkPlaceLocationMessages - sends messages to the work queues to create LaborFlowProbability matrices
     * iterates through all household and occupation segments,
     * bundles up workers in each combination of household and occupation
     * sends out messages: each node to run workplace location model on
     * one combination household segment/occupation group
     * Finally, add the unemployed persons to the person array.
     */
    private void createWorkplaceLocationMessages(){
        //Sort the person array by segment, occupation code so the first person will
        //have segment code 0, occupation code 0 followed by segment code 0, occupation code 1, etc.
        Arrays.sort(persons); //sorts persons by workSegment (0-8) and then by occupation code (0-8)

        //We want to find all persons that match a particular segment/occupation pair and send those
        //off to a worker to be processed.
        int index = 0; //index will keep track of where we are in the person array
        int nUnemployed = 0;
        int totalWorkers = 0;
        ArrayList unemployedPersonList = new ArrayList();
        ArrayList personList = new ArrayList();
        while(index < persons.length){
            int segment = persons[index].householdWorkSegment;
            int occupation = persons[index].occupation;
            int nPersons = 0;  //number of people in subgroup for the seg/occ pair.
            while(persons[index].householdWorkSegment == segment && persons[index].occupation == occupation){
                if(persons[index].employed){
                    if(persons[index].occupation == 0) logger.warning("Employed person has an occupation code of 'UNEMPLOYED'");
                    totalWorkers++;
                    nPersons++;
                    personList.add(persons[index]);
                    index++;
                }else{    //the person is unemployed - their occupation code may or may not be 0.
                    nUnemployed++;
                    unemployedPersonList.add(persons[index]);
                    index++; //go to next person
                }
                if(index == persons.length) break;  //the last person has been processed.
            }
            if(nPersons > 0){ //there were persons that matched the seg/occ pair (occ != 0)
                PTPerson[] personsSubset = new PTPerson[nPersons];
                personList.toArray(personsSubset);
                //create a message, set the occupation and segment
                Message laborFlowMessage = createMessage();
                laborFlowMessage.setId(MessageID.CALCULATE_WORKPLACE_LOCATIONS);
                laborFlowMessage.setValue("segment", new Integer(segment));
                laborFlowMessage.setValue("occupation", new Integer(occupation));
                laborFlowMessage.setValue("persons", personsSubset);
                String queueName = getQueueName2();
                logger.info("Sending Person Message to " + queueName + ": segment "
                        + segment + " - occupation " + occupation + ": total persons: " + nPersons);
                sendTo(queueName, laborFlowMessage);


                personList.clear(); //empty the array list so that we can put the
                                    //next group of persons in it.
            }
        }
        logger.info("Total persons: " + persons.length);
        logger.info("\tTotal unemployed persons: " + nUnemployed);
        logger.info("\tTotal working persons: " + totalWorkers);
        logger.info("Percent Unemployed: " + ((double)nUnemployed/persons.length)*100 + "%");
        personsWithWorkplaceCount = nUnemployed;   //used as a place holder for persons coming back from
                                                   //the workers.  They will be placed in the array
                                                   //starting at this number.

        //Once the entire list of persons has been processed, we need to put the unemployed
        // persons back into the persons array and convert the
        //message list into an array of messages so that they can be distributed to
        //the workers.
        Iterator iter = unemployedPersonList.iterator();
        while (iter.hasNext()){
            persons[nUnemployed-1] = (PTPerson) iter.next();  //the order doesn't matter so just start at
            nUnemployed--;                                //the array position corresponding to the nUnemployed-1
        }                                                 //and work backward.

        unemployedPersonList =  null;
        personList = null;
    }

    /**
     * Add the workers from the persons array in the message to the persons array
     * @param msg
     */
    private void addToPersonsArray(Message msg) {
        PTPerson[] ps = (PTPerson[]) msg.getValue("persons");
        for (int i = 0; i < ps.length; i++) {
            persons[personsWithWorkplaceCount] = ps[i];
            personsWithWorkplaceCount++;
        }
    }

    /**
     * Count the number of households, pre and post-secondary teachers
     * in each taz and store in arrays.  Send the arrays to each node
     * to update the data in memory.
     *
     */
    private void setTazDataArrays() {
        logger.info("Sending message to workers to update TAZ data");
    	int[] householdsByTaz = new int[MAXZONENUMBER+1];
        int[] postSecOccup = new int[MAXZONENUMBER+1];
        int[] otherSchoolOccup = new int[MAXZONENUMBER+1];

        for(int p=0;p<persons.length;p++){
            if(persons[p].occupation==OccupationCode.P0ST_SEC_TEACHERS)
                postSecOccup[persons[p].workTaz]++;
            else if(persons[p].occupation==OccupationCode.OTHER_TEACHERS)
                otherSchoolOccup[persons[p].workTaz]++;
        }
        for(int h=0;h<households.length;h++)
            householdsByTaz[households[h].homeTaz]++;



        for (int q = 1; q <= NUMBER_OF_WORK_QUEUES; q++) {
            Message tazInfo = createMessage();
            tazInfo.setId(MessageID.UPDATE_TAZDATA);
            tazInfo.setValue("householdsByTaz",householdsByTaz);
            tazInfo.setValue("postSecOccup",postSecOccup);
            tazInfo.setValue("otherSchoolOccup",otherSchoolOccup);
            String queueName = new String("WorkQueue" + q);
            logger.info("Sending Message to" + queueName +
                        " to update TAZ info");

            sendTo(queueName, tazInfo);
        }
    }

    /**
     * Creates tour destination choice logsums for non-work purposes.
     * Sends messages to workers to create dc logsums for a given purpose
     * and market segment.
     *
     */
    private void startDCLogsums() {
        logger.info("Creating tour destination choice logsums");

        //enter loop on purposes - start at 1 because you don't need to create DC logsums for work purposes
        for (int purpose = 1; purpose < mcPurposes.length(); ++purpose) {
            char thisPurpose = mcPurposes.charAt(purpose);

            //enter loop on segments
            for (int segment = 0; segment < TOTALSEGMENTS; ++segment) {
                Message dcLogsumMessage = createMessage();
                dcLogsumMessage.setId(MessageID.CREATE_DC_LOGSUMS);
                dcLogsumMessage.setValue("purpose",
                    new Character(mcPurposes.charAt(purpose)));
                dcLogsumMessage.setValue("segment", new Integer(segment));

                String queueName = getQueueName2();
                dcLogsumMessage.setValue("queue", queueName);
                logger.info("Sending DC logsums to " + queueName + " : " +
                    thisPurpose + segment);
                sendTo(queueName, dcLogsumMessage);
                totalDCLogsums++;
            }
        }
        if(debug) logger.info("Total DC Logsums sent out: " + totalDCLogsums);
    }
    
    /**
     * Starts the household processing by sending a specified number of household blocks to each work queue.
     * The second to last block will contain a message telling the worker node to send
     * a message back to PTDafMaster to send more households.
     *
     */
    private void startProcessHouseholds() {
        logger.fine("Processing households.");
         //Sort hhs by work segment, non-work segment
         Arrays.sort(households);

        int initalBlockSize = 20;

        //iterate through number of workers, 20 household blocks
        for (int q = 1; q <= NUMBER_OF_WORK_QUEUES; q++) {
            for (int j = 0;j <= initalBlockSize; j++){ //consider: *Math.ceil(households.length / NUMBER_OF_WORK_QUEUES / MAXBLOCKSIZE)

                //create an array of households
                PTHousehold[] householdBlock = new PTHousehold[MAXBLOCKSIZE];

                //fill it with households from the main household array
                for (int k = 0; k < MAXBLOCKSIZE; k++) {
                    if(householdCounter<households.length){
                        householdBlock[k] = (PTHousehold) households[householdCounter];
                        householdCounter++;
                    }
                }

                Message processHouseholds = createMessage();
                processHouseholds.setId(MessageID.PROCESS_HOUSEHOLDS);
                processHouseholds.setValue("households", householdBlock);

                //The "sendMore" key in the hashtable will be set to 1 for the second
                //to last block, else 0.
                if (j == (initalBlockSize - 2)) {
                    processHouseholds.setValue("sendMore", new Integer(1));
                } else {
                    processHouseholds.setValue("sendMore", new Integer(0));
                }

                logger.fine("sendMore: " +
                    (Integer) processHouseholds.getValue("sendMore"));

                String queueName = new String("WorkQueue" + q);
                processHouseholds.setValue("WorkQueue", queueName);
                logger.info("Sending Message to process households to " +
                    queueName);
                sendTo(queueName, processHouseholds);
                logger.fine("householdCounter = " + householdCounter);
                householdBlock = null;
            }
        }
    }

    /**
     * Send more households to workers
     * 
     * @param msg
     */
    private void sendMoreHouseholds(Message msg) {
        int secondaryBlockSize = 6;
        
        //set block size to min of secondary block size or remaining households/block size
        int thisBlockSize = Math.min(secondaryBlockSize,
                (int) Math.ceil(
                    (households.length - householdCounter) / MAXBLOCKSIZE));
        logger.fine("blockSize:" + thisBlockSize);

        for (int j = 0; j <= thisBlockSize; ++j) {
            int nextBlockSize = Math.min(MAXBLOCKSIZE,
                    households.length - householdCounter);
            logger.fine("blockSize:" + nextBlockSize);

            PTHousehold[] householdBlock = new PTHousehold[nextBlockSize];

            for (int k = 0; k < nextBlockSize; k++) {
                if(householdCounter<households.length){
                    householdBlock[k] = (PTHousehold) households[householdCounter];
                    householdCounter++;
                }
            }

            Message processHouseholds = createMessage();
            processHouseholds.setId(MessageID.PROCESS_HOUSEHOLDS);
            processHouseholds.setValue("households", householdBlock);

            if (j == (thisBlockSize - 2)) {
                processHouseholds.setValue("sendMore", new Integer(1));
            } else {
                processHouseholds.setValue("sendMore", new Integer(0));
            }

            String queueName = (String) msg.getValue("WorkQueue");
            processHouseholds.setValue("WorkQueue", queueName);
            logger.info("Sending Message to process households to " +
                queueName);
            sendTo(queueName, processHouseholds);
            logger.info("householdCounter = " + householdCounter);
            householdBlock = null;
        }
    }

    /**
     * getQueueName() is used when spraying an equal number of messages accross all WorkQueues.
     */
    private String getQueueName() {
        if (lastWorkQueue == NUMBER_OF_WORK_QUEUES_AGGREGATE) {
            int thisWorkQueue = 1;
            String queue = new String("WorkQueue" + thisWorkQueue);
            lastWorkQueue = thisWorkQueue;

            return queue;
        } else {
            int thisWorkQueue = lastWorkQueue + 1;
            String queue = new String("WorkQueue" + thisWorkQueue);
            lastWorkQueue = thisWorkQueue;

            return queue;
        }
    }

    /**
     * getQueueName() is used when spraying an equal number of messages accross all WorkQueues.
     */
    private String getQueueName2() {
        if (lastWorkQueue == NUMBER_OF_WORK_QUEUES) {
            int thisWorkQueue = 1;
            String queue = new String("WorkQueue" + thisWorkQueue);
            lastWorkQueue = thisWorkQueue;

            return queue;
        } else {
            int thisWorkQueue = lastWorkQueue + 1;
            String queue = new String("WorkQueue" + thisWorkQueue);
            lastWorkQueue = thisWorkQueue;

            return queue;
        }
    }

    public static void main(String[] args) {

        Logger logger = Logger.getLogger("com.pb.despair.pt");
        ResourceBundle rb = ResourceUtil.getPropertyBundle(new File("/models/tlumip/scenario_pleaseWork/t1/pt/pt.properties"));
        //Read the SynPop data
        PTDataReader dataReader = new PTDataReader(rb);
        logger.info("Adding synthetic population from database");
        PTHousehold[] households = dataReader.readHouseholds("households.file");
        int totalHouseholds = households.length;
        logger.info("Total Number of HHs :" + totalHouseholds);

        logger.info("Reading the Persons file");
        PTPerson[] persons = dataReader.readPersons("persons.file");

        //add worker info to Households and add homeTAZ and hh work segment to persons
        //This method sorts households and persons by ID.  Leaves arrays in sorted positions.
        dataReader.addPersonInfoToHouseholdsAndHouseholdInfoToPersons(households,
                persons);

        //Sort hhs by work segment, non-work segment
        Arrays.sort(households);

        int[][] workByNonWork = PTSummarizer.summarizeHHsByWorkAndNonWorkSegments(households);
        System.out.println("work,0,1,2,3,4,5,6,7,8");
        String row = "";
        for(int r=0; r<workByNonWork.length; r++){
            row+=r+",";
            for(int c=0; c<workByNonWork[0].length; c++){
                row+= workByNonWork[r][c]+",";
            }
            System.out.println(row);
            row="";
        }
    }
}
