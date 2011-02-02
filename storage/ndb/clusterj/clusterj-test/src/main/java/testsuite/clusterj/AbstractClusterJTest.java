/*
   Copyright (c) 2010, Oracle and/or its affiliates. All rights reserved.

   This program is free software; you can redistribute it and/or modify
   it under the terms of the GNU General Public License as published by
   the Free Software Foundation; version 2 of the License.

   This program is distributed in the hope that it will be useful,
   but WITHOUT ANY WARRANTY; without even the implied warranty of
   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
   GNU General Public License for more details.

   You should have received a copy of the GNU General Public License
   along with this program; if not, write to the Free Software
   Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301  USA
*/

package testsuite.clusterj;

import com.mysql.clusterj.ClusterJException;
import com.mysql.clusterj.ClusterJHelper;
import com.mysql.clusterj.Constants;
import com.mysql.clusterj.Session;
import com.mysql.clusterj.SessionFactory;
import com.mysql.clusterj.Transaction;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.Thread.UncaughtExceptionHandler;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Properties;

import junit.framework.TestCase;

/**
 *
 */
public abstract class AbstractClusterJTest extends TestCase {
    protected static final String JDBC_DRIVER_NAME = "jdbc.driverName";
    protected static final String JDBC_URL = "jdbc.url";
    protected static Connection connection;
    protected static String jdbcDriverName;
    protected static String jdbcPassword;
    protected static String jdbcURL;
    protected static String jdbcUsername;
    protected static Properties props;
    protected static List<String> schemaDefinition = new ArrayList<String>();
    /** Has the schema been initialized */
    protected static boolean schemaInitialized = false;
    String PROPS_FILE_NAME = System.getProperty("clusterj.properties", "clusterj.properties");
    protected Session session;
    protected SessionFactory sessionFactory;
    protected Transaction tx;
    /**
     *
     * Error messages collected during a test.
     */
    private StringBuffer errorMessages;
    /**
     *
     * A list of registered pc classes.
     * The extents of these classes are deleted in <code>tearDown</code>.
     */
    private Collection<Class<?>> tearDownClasses = new LinkedList<Class<?>>();
    /**
     *
     * A list of registered oid instances.
     * Corresponding pc instances are deleted in <code>localTearDown</code>.
     */
    private Collection<Object> tearDownInstances = new LinkedList<Object>();

    /**
     *
     * Indicates an exception thrown in method <code>tearDown</code>.
     * At the end of method <code>tearDown</code> this field is nullified.
     * TODO support this feature
     */
//    private Throwable tearDownThrowable;
    private String NL = "\n";

    protected boolean debug;

    /** Subclasses can override this method to get debugging info printed to System.out */
    protected boolean getDebug() {
        return false;
    }

    public AbstractClusterJTest() {
        debug = getDebug();
    }

    protected void addTearDownClasses(Class<?>... classes) {
        for (Class<?> cls : classes) {
            tearDownClasses.add(cls);
        }
    }

    protected void createSessionFactory() {
        loadProperties();
        Properties modifiedProperties = modifyProperties();
        if (debug) System.out.println("createSessionFactory props: " + modifiedProperties);
        if (sessionFactory == null) {
            sessionFactory = ClusterJHelper.getSessionFactory(modifiedProperties);
        }
        loadSchema();
    }

    protected Properties modifyProperties() {
        // doesn't do anything but can be overridden by a subclass
        return props;
    }

    public void createSession() {
        if (session != null && !session.isClosed()) {
            tx = session.currentTransaction();
            if (tx.isActive()) {
                tx.commit();
            }
            session.close();
        }
        session = sessionFactory.getSession();
        tx = session.currentTransaction();
    }

    protected void dumpSystemProperties() {
        Properties sysprops = System.getProperties();
        List<Entry<Object, Object>> entries = new ArrayList<Entry<Object, Object>>(sysprops.entrySet());
        Collections.sort(entries, new Comparator<Entry<Object, Object>>() {

            public int compare(Entry<Object, Object> o1, Entry<Object, Object> o2) {
                return ((String) o1.getKey()).compareToIgnoreCase((String) o2.getKey());
            }
        });
        for (Iterator<Entry<Object, Object>> iterator = entries.iterator(); iterator.hasNext();) {
            Entry<Object, Object> entry = iterator.next();
            System.out.println("key: " + entry.getKey() + "; value: " + entry.getValue());
        }
    }

    protected void error(String message) {
        initializeErrorMessages();
        errorMessages.append(message + NL);
    }

    protected void error(String context, Exception ex) {
        String message = context + " " + ex.getClass().getName() + ":" + ex.getMessage();
        error(message);
        if (getDebug()) {
            ex.printStackTrace();
        }
    }

    protected void errorIfNotEqual(String message, Object expected, Object actual) {
        if (expected == null && actual == null) {
            return;
        }
        if (expected != null && expected.equals(actual)) {
            return;
        } else {
            initializeErrorMessages();
            errorMessages.append(message + NL);
            errorMessages.append(
                    "Expected: " + ((expected==null)?"null":expected.toString())
                    + " actual: " + ((actual==null)?"null":actual.toString()) + NL);
        }
    }

    protected void errorIfEqual(String message, Object expected, Object actual) {
        if (expected == null && actual != null) {
            return;
        }
        if (expected != null && !expected.equals(actual)) {
            return;
        } else {
            initializeErrorMessages();
            errorMessages.append(message + NL);
            errorMessages.append(
                    "Error value: " + ((expected==null)?"null":expected.toString()));
        }
    }

    protected void failOnError() {
        if (errorMessages != null) {
            fail(errorMessages.toString());
        }
    }

    /** Close the connection and reset the connection variable.
     * 
     */
    protected void closeConnection() {
        try {
            if (connection != null) {
                connection.close();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Caught SQLException during close.", e);
        } finally {
            connection = null;
        }
    }

    /** Get a connection with special properties. If the connection is open,
     * close it and get a new one.
     * 
     */
    protected void getConnection(Properties extraProperties) {
        // characterEncoding = utf8 property is especially useful
        Properties properties = new Properties();
        properties.put("user", jdbcUsername);
        properties.put("password", jdbcPassword);
        properties.putAll(extraProperties);
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
                connection = null;
            }
            if (debug) System.out.println("Getting new connection with properties " + properties);
            connection = DriverManager.getConnection(jdbcURL, properties);
        } catch (SQLException ex) {
            ex.printStackTrace();
            throw new ClusterJException("Exception getting connection to " + jdbcURL + "; username " + jdbcUsername, ex);
            // TODO Auto-generated catch block
        }
    }

    /** Get a connection with properties from the Properties instance.
     * 
     */
    protected Connection getConnection() {
        if (connection == null) {
            try {
                Class.forName(jdbcDriverName, true, Thread.currentThread().getContextClassLoader());
                connection = DriverManager.getConnection(jdbcURL, jdbcUsername, jdbcPassword);
            } catch (SQLException ex) {
                throw new ClusterJException("Exception getting connection to " + jdbcURL + "; username " + jdbcUsername, ex);
            } catch (ClassNotFoundException ex) {
                throw new ClusterJException("Exception loading JDBC driver." + jdbcDriverName, ex);
            }
        }
        return connection;
    }

    /** Get a connection with properties from a file.
     * 
     * @param propertiesFileName the name of the properties file
     */
    protected void getConnection(String propertiesFileName) {
        loadProperties(propertiesFileName);
        loadDriver();
        String url = props.getProperty(JDBC_URL);
        try {
            connection = DriverManager.getConnection(url);
            setAutoCommit(connection, false);
        } catch (SQLException e) {
            throw new RuntimeException("Could not get Connection: " + url, e);
        }
    }

    /**
     * @throws ClassNotFoundException
     */
    protected void loadDriver() {
        String driverName = props.getProperty(JDBC_DRIVER_NAME);
        try {
            Class.forName(driverName);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Class not found: " + driverName, e);
        }
    }

    protected void setAutoCommit(Connection connection, boolean b) {
        try {
            connection.setAutoCommit(false);
        } catch (SQLException e) {
            throw new RuntimeException("setAutoCommit failed", e);
        }
    }

    Properties getProperties(String fileName) {
        Properties result = null;
        try {
            InputStream stream = new FileInputStream(new File(fileName));
            result = new Properties();
            result.load(stream);
            return result;
        } catch (FileNotFoundException ex) {
            // ignore and try getResourceAsStream
        } catch (IOException ex) {
            // ignore and try getResourceAsStream
        }
        if (result == null) {
            try {
                // try to load the resource from the class loader
                ClassLoader cl = this.getClass().getClassLoader();
                InputStream stream = cl.getResourceAsStream(fileName);
                result = new Properties();
                result.load(stream);
                return result;
            } catch (IOException ex) {
                fail("Could not create ConnectionFactory " + ex);
            } catch (NullPointerException ex) {
                fail("Missing properties file " + fileName);
            }
        }
        return null;
    }

    protected void initializeErrorMessages() {
        if (errorMessages == null) {
            errorMessages = new StringBuffer();
            errorMessages.append(NL);
        }
    }

    /** Initialize the JDBC driver */
    protected void initializeJDBC() {
        loadProperties();
        getConnection();
    }

    protected void initializeSchema() {
        getConnection();
        Iterator<String> it = schemaDefinition.iterator();
        // skip past drop table
        it.next();
        // skip past test table
        it.next();
        String statement = null;
        try {
            while (it.hasNext()) {
                statement = it.next();
                if (debug) System.out.println("Executing statement " + statement + ";");
                PreparedStatement s = connection.prepareStatement(statement);
                s.execute();
                s.close();
            }
            schemaInitialized = true;
//            connection.close();
            System.out.println("Successfully initialized schema.");
        } catch (SQLException ex) {
            // on failure, drop the test table so we try again
            resetSchema();
            throw new ClusterJException("initializeSchema threw exception on " + statement, ex);
        }
    }

    /** Load properties from clusterj.properties */
    protected void loadProperties() {
        loadProperties(PROPS_FILE_NAME);
    }

    /** Load properties from an arbitrary file name */
    protected void loadProperties(String propsFileName) {
//        if (props == null) {
            props = getProperties(propsFileName);
//        }
        jdbcDriverName = props.getProperty(Constants.PROPERTY_JDBC_DRIVER_NAME);
        jdbcURL = props.getProperty(Constants.PROPERTY_JDBC_URL);
        jdbcUsername = props.getProperty(Constants.PROPERTY_JDBC_USERNAME);
        jdbcPassword = props.getProperty(Constants.PROPERTY_JDBC_PASSWORD);
        if (jdbcPassword == null) {
            jdbcPassword = "";
        }
    }

    /** Load the schema for tests */
    protected void loadSchema() {
        initializeJDBC();
        if (!schemaInitialized) {
            loadSchemaDefinition();
            if (!testSchema()) {
                initializeSchema();
            }
        }
    }

    protected void loadSchemaDefinition() {
        InputStream inputStream = null;
        StringBuffer buffer = new StringBuffer();
        String line;
        try {
            inputStream = Thread.currentThread().getContextClassLoader().getResourceAsStream("schema.sql");
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
            while (reader.ready()) {
                line = reader.readLine();
                if (line.contains("#")) {
                    // comment line; ignore
                    continue;
                }
                int semi = line.indexOf(";");
                if (semi != -1) {
                    buffer.append(line.substring(0, semi));
                    schemaDefinition.add(buffer.toString());
                    buffer = new StringBuffer();
                } else {
                    buffer.append(line);
                }
            }
        } catch (IOException ex) {
            throw new ClusterJException("Exception reading schema.sql.", ex);
        } finally {
            try {
                if (inputStream != null) {
                    inputStream.close();
                }
            } catch (IOException ex) {
            }
        }
    }

    /**
     * Subclasses may override this method to allocate any data and resources
     * that they need in order to successfully execute this testcase.
     * Adding teardown classes and instances is done in the overridden method.
     */
    protected void localSetUp() {
    }

    /**
     * Subclasses may override this method to deallocate any data and resources
     * that they needed in order to successfully execute this testcase.
     */
    protected void localTearDown() {
    }

    @Override
    protected final void setUp() throws Exception {
        localSetUp();
    }

    @Override
    protected final void tearDown() throws Exception {
        localTearDown();
        // if session is null or closed, test class has already cleaned up
        if (session != null && !(session.isClosed())) {
            // if tx is null, get it again
            if (tx == null) {
                tx = session.currentTransaction();
            }
            // if transaction is active (leftover), roll it back
            if (tx.isActive()) {
                tx.rollback();
            }
            // if any work to do, start a transaction and clean up
            if (!tearDownClasses.isEmpty() | !tearDownInstances.isEmpty()) {
                tx.begin();
                for (Class<?> cls : tearDownClasses) {
                    session.deletePersistentAll(cls);
                }
                for (Object o : tearDownInstances) {
                    session.deletePersistent(o);
                }
                tx.commit();
                session.close();
                session = null;
            }
        }
        session = null;
        sessionFactory = null;
    }

    protected void removeAll(Class<?> cls) {
        sessionFactory.getSession();
        session.currentTransaction().begin();
        session.deletePersistentAll(cls);
        session.currentTransaction().commit();
    }

    protected boolean testSchema() {
        try {
            PreparedStatement ps = connection.prepareStatement(schemaDefinition.get(1));
            ps.execute();
            ps.close();
            return true;
        } catch (SQLException ex) {
            if (debug) ex.printStackTrace();
            System.out.println("Test schema failed (normal)" + schemaDefinition.get(1));
            return false;
        }
    }

    protected boolean resetSchema() {
        try {
            PreparedStatement ps = connection.prepareStatement(schemaDefinition.get(0));
            ps.execute();
            ps.close();
            return true;
        } catch (SQLException ex) {
            System.out.println("Test schema failed (normal)" + schemaDefinition.get(0));
            return false;
        }
    }

    protected static String dump(String string) {
        StringBuffer buffer = new StringBuffer("[");
        for (int i = 0; i < string.length(); ++i) {
            int theCharacter = string.charAt(i);
            buffer.append(theCharacter);
            buffer.append(" ");
        }
        buffer.append("]");
        return buffer.toString();
    }

    protected String dump(List<String> list) {
        StringBuffer result = new StringBuffer();
        for (String string: list) {
            result.append(dump(string));
            result.append('\n');
        }
        return result.toString();
    }

    /** Catch otherwise uncaught exceptions and maintain a list of them.
     * When needed, they can be obtained via the getUncaughtExceptions method.
     */
    public static class MyUncaughtExceptionHandler implements UncaughtExceptionHandler {
        private static List<Throwable> uncaughtExceptions = new ArrayList<Throwable>();
        public List<Throwable> getUncaughtExceptions() {
            return uncaughtExceptions;
        }
        public synchronized void uncaughtException(Thread t, Throwable e) {
            {
                uncaughtExceptions.add(e);
            }
        }
    }

}
