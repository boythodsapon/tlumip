/*
 * Copyright  2005 PB Consult Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package com.pb.tlumip.pt.daf;

/**
 * UpdateTazdataTask
 *
 * @author Freedman
 * @version Aug 11, 2004
 * 
 */


import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ResourceBundle;
import org.apache.log4j.Logger;
import java.util.Date;

import com.pb.common.daf.Message;
import com.pb.common.daf.MessageProcessingTask;
import com.pb.common.util.ResourceUtil;
import com.pb.tlumip.pt.PTModelInputs;

public class UpdateTazdataTask extends MessageProcessingTask{
    final static Logger logger = Logger.getLogger("com.pb.tlumip.pt.daf");
    protected static Object lock = new Object();
    protected static ResourceBundle ptRb;
    protected static ResourceBundle globalRb;
    protected static boolean initialized = false;
    String fileWriterQueue = "FileWriterQueue";

    //these arrays are to store the information necessary to update PTModelNew.tazData
    public static int[] householdsByTaz;
    public static int[] postSecOccup;
    public static int[] otherSchoolOccup;

    /**
     * Onstart method sets up model
     */
    public void onStart() {
        synchronized (lock) {
            logger.info( "***" + getName() + " started");
            //in cases where there are multiple tasks in a single vm, need to make sure only initilizing once!
            if (!initialized) {
                String scenarioName;
                int timeInterval;
                String pathToPtRb;
                String pathToGlobalRb;
                
                logger.info("Reading RunParams.properties file");
                ResourceBundle runParamsRb = ResourceUtil.getResourceBundle("RunParams");
                scenarioName = ResourceUtil.getProperty(runParamsRb,"scenarioName");
                logger.info("\tScenario Name: " + scenarioName);
                timeInterval = Integer.parseInt(ResourceUtil.getProperty(runParamsRb,"timeInterval"));
                logger.info("\tTime Interval: " + timeInterval);
                pathToPtRb = ResourceUtil.getProperty(runParamsRb,"pathToAppRb");
                logger.info("\tResourceBundle Path: " + pathToPtRb);
                pathToGlobalRb = ResourceUtil.getProperty(runParamsRb,"pathToGlobalRb");
                logger.info("\tResourceBundle Path: " + pathToGlobalRb);
                
                
                ptRb = ResourceUtil.getPropertyBundle(new File(pathToPtRb));
                globalRb = ResourceUtil.getPropertyBundle(new File(pathToGlobalRb));

                PTModelInputs ptInputs = new PTModelInputs(ptRb,globalRb);
                logger.info("Setting up the aggregate mode choice model");
                ptInputs.setSeed(2002);
                ptInputs.getParameters();
                ptInputs.readSkims();
                ptInputs.readTazData();

                initialized = true;
            }

            logger.info( "***" + getName() + " finished onStart()");
        }
    }
    /**
     * A worker bee that will process a block of households.
     *
     */
    public void onMessage(Message msg) {
        logger.info("********" + getName() + " received messageId=" + msg.getId() +
            " message from=" + msg.getSender() + " at " + new Date());

        if (msg.getId().equals(MessageID.UPDATE_TAZDATA)){
                    updateTazData(msg);
                }
    }

        public void updateTazData(Message msg){
            logger.info("Updating TAZ info");
            householdsByTaz = new int[((int[])msg.getValue("householdsByTaz")).length];
            for(int i=0;i<((int[])msg.getValue("householdsByTaz")).length;i++){
                householdsByTaz[i] = ((int[])msg.getValue("householdsByTaz"))[i];
            }
            postSecOccup = new int[((int[])msg.getValue("postSecOccup")).length];
            for(int i=0;i<((int[])msg.getValue("postSecOccup")).length;i++){
                postSecOccup[i] = ((int[])msg.getValue("postSecOccup"))[i];
            }
            otherSchoolOccup = new int[((int[])msg.getValue("otherSchoolOccup")).length];
            for(int i=0;i<((int[])msg.getValue("otherSchoolOccup")).length;i++){
                otherSchoolOccup[i] = ((int[])msg.getValue("otherSchoolOccup"))[i];
            }

            PTModelInputs.tazs.setPopulation(householdsByTaz);
            PTModelInputs.tazs.setSchoolOccupation(otherSchoolOccup,postSecOccup);
            PTModelInputs.tazs.collapseEmployment(PTModelInputs.tdpd,PTModelInputs.sdpd);

            Message tazUpdatedMsg = createMessage();
            tazUpdatedMsg.setId(MessageID.TAZDATA_UPDATED);
            sendTo("TaskMasterQueue",tazUpdatedMsg);
        }

}
