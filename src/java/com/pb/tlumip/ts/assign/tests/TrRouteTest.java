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
package com.pb.tlumip.ts.assign.tests;

/**
 *
 * @author    Jim Hicks
 * @version   1.0, 5/7/2004
 */


import com.pb.common.util.ResourceUtil;
import com.pb.tlumip.ts.assign.Network;
import com.pb.tlumip.ts.transit.TrRoute;

//import com.pb.common.util.ResourceUtil;

import java.util.HashMap;
import java.util.Date;
import java.text.DateFormat;
import org.apache.log4j.Logger;



public class TrRouteTest {

	protected static Logger logger = Logger.getLogger(TrRouteTest.class);

	public static final String PK_TRANSIT_ROUTES_D221 = "c:\\jim\\projects\\tlumip\\NetworkData\\pktran.in";
	public static final String ROUTE_REPORT_FILE = "c:\\jim\\projects\\tlumip\\Tlumip_Routes.report";
	

	
	HashMap tsPropertyMap;
    HashMap globalPropertyMap;

	Network g = null;
	
	
	
	public TrRouteTest() {

	    tsPropertyMap = ResourceUtil.getResourceBundleAsHashMap("tsTest");
        globalPropertyMap = ResourceUtil.getResourceBundleAsHashMap("globalTest");

	}
    
	
	
    public static void main (String[] args) {
        
		TrRouteTest test = new TrRouteTest();

		test.runTest("peak");
		
    }

    
    
    private void runTest (String period) {
        
		long startTime = System.currentTimeMillis();
		String myDateString;
		
		// create a highway network oject
		Network g = new Network( tsPropertyMap, globalPropertyMap, period );

		// create transit routes object with max 50 routes
		TrRoute tr = new TrRoute (500);

		//read transit route info from Emme/2 for d221 file
		tr.readTransitRoutes ( PK_TRANSIT_ROUTES_D221 );

		// associate transit segment node sequence with highway link indices
		tr.getLinkIndices (g);

		// print route summary file
		tr.printTransitRoutes ( ROUTE_REPORT_FILE );

        
		myDateString = DateFormat.getDateTimeInstance().format(new Date());
		logger.info ("done at: " + myDateString);


		logger.info("TrRouteTest() finished in " +
			((System.currentTimeMillis() - startTime) / 60000.0) + " minutes");

    }
    
}
