package com.pb.despair.pt.tripmodes;
import com.pb.despair.model.Mode;
import com.pb.despair.model.ModeType;
import com.pb.despair.model.TravelTimeAndCost;
import com.pb.despair.pt.Activity;
import com.pb.despair.pt.PersonTripModeAttributes;
import com.pb.despair.pt.TripModeParameters;
import com.pb.despair.pt.ZoneAttributes;

import java.util.logging.Logger;
/**  
 * Passenger (three+ person shared ride) mode
 * 
 * @author Joel Freedman
 * @version 1.0 12/01/2003
 * 
 */
public class SharedRide3Plus extends Mode {
    protected static Logger logger = Logger.getLogger("com.pb.despair.pt.default");     
     
//     public boolean isAvailable=true;
//     public boolean hasUtility=false;
//     double utility=0;
     public SharedRide3Plus(){
         isAvailable = true;
                 hasUtility = false;
                 utility = 0.0D;
          alternativeName=new String("SharedRide3Plus");
          type=ModeType.SHAREDRIDE3PLUS;
     }

    /** Calculates utility of two person shared ride mode
     * 
     * @param inbound - In-bound TravelTimeAndCost
     * @param outbound - Outbound TravelTimeAndCost
     * @param z - ZoneAttributes (Currently only parking cost)
     * @param c - TourModeParameters
     * @param p - PersonTourModeAttributes
     */
     
     public void calcUtility(TravelTimeAndCost tc, ZoneAttributes z,TripModeParameters c, PersonTripModeAttributes p, Mode tourMode,
          Activity thisActivity){

        hasUtility = false;
              utility=-999;
              isAvailable = true;

          if(tc.sharedRide3Time==0) isAvailable=false;
          if(tourMode.type!=ModeType.AUTODRIVER && tourMode.type!=ModeType.AUTOPASSENGER
             && tourMode.type!=ModeType.TRANSITPASSENGER && tourMode.type!=ModeType.PASSENGERTRANSIT)
               isAvailable=false;
               
          int autoDriver=0;
          int autoPassenger=0;
          if(tourMode.type==ModeType.AUTODRIVER)
               autoDriver=1;
          else
               autoPassenger=1;
               
          if(isAvailable){
               time=tc.sharedRide3Time;
               utility=(
                 c.ivt*tc.sharedRide3Time
                 + c.opcpas*tc.sharedRide3Cost
                 + c.opcpas*(z.parkingCost*(thisActivity.duration/60))
                 + c.sr3hh3p*p.size3p
                  + c.driverSr3p*autoDriver
                  + c.passSr3p*autoPassenger
               );
               hasUtility=true;
          };
               
     };

     public double getUtility(){
          if(!hasUtility){
              logger.severe("Error: Utility not calculated for "+alternativeName+"\n");
               System.exit(1);
          };
          return utility;
     };
     

}

