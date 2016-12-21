package com.charityusa.Abbadon;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.InstanceNotFoundException;
import javax.management.MBeanException;
import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.MalformedObjectNameException;
import javax.management.ReflectionException;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import java.io.IOException;
import java.util.Set;


/**
 * Created with IntelliJ IDEA.
 * User: cjworden
 * Date: 9/27/13
 * Time: 1:26 PM
 */
public class Abbadon {
    @SuppressWarnings("FieldCanBeLocal")
    private static final int TIME_USED_INTERVAL = 5; // Cutoff for deciding if a session was a single page view or multiple requests
    @SuppressWarnings("FieldCanBeLocal")
    private static final int TIME_INACTIVE_THRESHOLD_SMALL = 120;// Strict value to determine if a user has been inactive long enough to expire their session
    @SuppressWarnings("FieldCanBeLocal")
    private static final int TIME_INACTIVE_THRESHOLD_LARGE = 600;// Loose value to determine if a user has been inactive long enough to expire their session
    private static final String[] STRING_SIGNATURE = {"java.lang.String"};
    private static final String[] MANAGEMENT_APPS = {"manager", "probe"};
    private static Logger logger = LoggerFactory.getLogger(Abbadon.class);
    private static MBeanServerConnection mbsc;

    public static void main(String[] args) {
        try {
            JMXServiceURL url = new JMXServiceURL("service:jmx:rmi:///jndi/rmi://localhost:1099/jmxrmi");
            JMXConnector jmxc = JMXConnectorFactory.connect(url, null);
            mbsc = jmxc.getMBeanServerConnection();
        } catch (IOException e) {
            logger.error("Failed to initialize JMX connection.");
            System.exit(1);
        }

        Set<ObjectName> names = filteredObjectNames(MANAGEMENT_APPS);
        if (names == null) {
            // An error occurred while getting the contexts, nothing we can do here and its already been reported.
            return;
        }
        expireSessions(names);
    }

    /**
     * Gets the names of Catalina MBeans excluding those which have a context matching any entry in the passed toFilter array.
     * @param toFilter Contexts to filter out of the returned list.
     * @return A set containing the ObjectNames for the Catalina MBeans with the filter applied. If no MBean satisfies the query, an empty list is returned.
     */
    private static Set<ObjectName> filteredObjectNames(String[] toFilter){
        try {
            Set<ObjectName> names = mbsc.queryNames(ObjectName.getInstance("Catalina:type=Manager,host=localhost,context=*"), null);
            for(String filter : toFilter) {
                names.remove(ObjectName.getInstance("Catalina:type=Manager,host=localhost,context=/" + filter));
            }
            logger.debug("Final set:" + names);
            return names;
        } catch (MalformedObjectNameException | java.io.IOException e) {
            logger.error("Exception getting bean names from JMX connection.", e);
            return null;
        }
    }

    /**
     * Expires sessions on the apps represented in the given bean ObjectNames.
     * @param names ObjectName representation of beans containing session info for each app.
     */
    private static void expireSessions(Set<ObjectName> names) {
        // Expire sessions
        while(true) {
            int serverWaitTime = 0;

            for (ObjectName name : names) {
                logger.info("Processing sessions for " + name + "");
                String sessionIds = "";
                try {
                    sessionIds = (String) mbsc.invoke(name, "listSessionIds", null, null);
                } catch (Exception e) {
                    logger.error("Error getting session IDs from server.", e);
                }
                if(sessionIds.equals("")) {
                    logger.debug("No sessions found");
                    continue;
                }
                String[] sessions = sessionIds.split(" ");


                for (String sessionID : sessions) {
                    logger.debug("\t" + sessionID);

                    Session session = new Session(name, sessionID);

                    // Debug prints
                    logger.debug("\t\tCreated: " + session.getCreationTime());
                    logger.debug("\t\tLast Accessed: " + session.getLastAccessedTime());
                    logger.debug("\t\tUsed time: " + session.getDisplayUsedTime());
                    logger.debug("\t\tInactive time " + session.getDisplayInactiveTime());


                    if (session.getUsedTime() < TIME_USED_INTERVAL) {
                        logger.debug("\t\tSinge page request");
                        if (session.getInactiveTime() >= TIME_INACTIVE_THRESHOLD_SMALL){
                            logger.debug("\t\tInvalidating session");
                            session.invalidate();
                        } else {
                            int sessionWaitTime = TIME_INACTIVE_THRESHOLD_SMALL - session.getInactiveTime();
                            serverWaitTime = (serverWaitTime > sessionWaitTime) ? serverWaitTime : sessionWaitTime;
                        }
                    }else {
                        logger.debug("\t\tUser is getting stuff done");
                        if (session.getInactiveTime() >= TIME_INACTIVE_THRESHOLD_LARGE){
                            session.invalidate();
                        } else {
                            int sessionWaitTime = TIME_INACTIVE_THRESHOLD_LARGE - session.getInactiveTime();
                            serverWaitTime = (serverWaitTime > sessionWaitTime) ? serverWaitTime : sessionWaitTime;
                        }
                    }
                }
            }

            if(serverWaitTime == 0){
                break;
            }
            try {
                Thread.sleep(serverWaitTime * 1000);
            } catch (InterruptedException ie){
                // We don't really care if another thread wakes us up early (don't know why they would...) we can just recheck our sessions early and resleep at the end of the loop.
            }
        }
        logger.info("Sessions expired");
    }



    /**
     * Session representation holding the session ID and MBean as stored in Tomcat in addition to methods to access other session info.
     */
    private static class Session {
        private long creationTime;
        private long lastAccessedTime;
        private final String sessionID;
        private final ObjectName objectName;

        private Session(ObjectName objectName, String sessionID){
            this.sessionID = sessionID;
            this.objectName = objectName;

            String[] params = {sessionID};
            try {
                this.creationTime = ((Number) mbsc.invoke(objectName, "getCreationTimestamp", params, STRING_SIGNATURE)).longValue();
                this.lastAccessedTime = ((Number) mbsc.invoke(objectName, "getLastAccessedTimestamp", params, STRING_SIGNATURE)).longValue();
            } catch (InstanceNotFoundException | MBeanException | ReflectionException | java.io.IOException e) {
                e.printStackTrace();
            }
        }

        private long getCreationTime() {
            return creationTime;
        }

        private long getLastAccessedTime() {
            return lastAccessedTime;
        }

        private int getUsedTime(){
            return ((Number) calculateUsedTime()).intValue() / 1000;

        }

        private int getInactiveTime(){
            return ((Number) calculateInactiveTime()).intValue() / 1000;
        }

        /**
         * Invalidates the session.
         */
        private void invalidate() {
            logger.debug("KILL KILL KILL!!!");
            String[] params = {sessionID};
            try {
                mbsc.invoke(objectName, "expireSession", params, STRING_SIGNATURE);
            } catch(Exception e){
                e.printStackTrace();
            }
        }

        /**
         *
         * @return A print friendly String of how long the session was used.
         */
        private  String getDisplayUsedTime() {
            if (creationTime == 0) {
                return "";
            }
            return secondsToTimeString(calculateUsedTime()/1000);
        }

        /**
         *
         * @return The number of milliseconds the session was used.
         */

        private long calculateUsedTime() {
            return lastAccessedTime - creationTime;
        }

        /**
         *
         * @return A print friendly String of how long the session has been inactive.
         */
        private String getDisplayInactiveTime() {
            return secondsToTimeString(calculateInactiveTime()/1000);
        }

        /**
         *
         * @return How long the session has been inactive.
         */
        private long calculateInactiveTime() {
            return System.currentTimeMillis() - lastAccessedTime;
        }

        /**
         * Converts a long of seconds to a String, formatted as HH:MM:SS.
         * @param seconds Seconds value to convert to String
         * @return Number of seconds converted to String in HH:MM:SS format.
         */
        private String secondsToTimeString(long seconds) {
            StringBuilder buff = new StringBuilder(9);
            if (seconds < 0) {
                buff.append('-');
                seconds = -seconds;
            }
            long rest = seconds;
            long hour = rest / 3600;
            rest = rest % 3600;
            long minute = rest / 60;
            rest = rest % 60;
            long second = rest;
            if (hour < 10) {
                buff.append('0');
            }
            buff.append(hour);
            buff.append(':');
            if (minute < 10) {
                buff.append('0');
            }
            buff.append(minute);
            buff.append(':');
            if (second < 10) {
                buff.append('0');
            }
            buff.append(second);
            return buff.toString();
        }
    }
}
