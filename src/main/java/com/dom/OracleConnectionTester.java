package com.dom;

import jdk.nashorn.internal.runtime.ParserException;
import oracle.jdbc.OracleDriver;
import oracle.jdbc.pool.OracleDataSource;
import oracle.security.pki.OracleWallet;
import oracle.security.pki.textui.OraclePKIGenFunc;
import oracle.ucp.jdbc.PoolDataSource;
import oracle.ucp.jdbc.PoolDataSourceFactory;
import org.apache.commons.cli.*;

import javax.crypto.Cipher;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static java.util.logging.Level.FINE;

public class OracleConnectionTester {

    private static final Logger logger = Logger.getLogger(OracleConnectionTester.class.getName());

    private enum CommandLineOptions {
        USERNAME,
        PASSWORD,
        CONNECT_STRING,
        THREAD_COUNT,
        CONNECTION_TYPE,
        DRIVER_TYPE
    }

    private enum ConnectionType {
        PDS,
        ODS
    }

    private enum DriverType {
        oci,
        thin
    }

    private static Connection connect(String un, String pw, String cs) throws RuntimeException, Error {
        // Standard dedicated connection to Oracle
        try {
            OracleDataSource ods = new OracleDataSource();
            ods.setUser(un);
            ods.setPassword(pw);
            ods.setURL(cs);
            Properties connectionProperties = new Properties();
            connectionProperties.setProperty("autoCommit", "false");
            connectionProperties.setProperty("oracle.jdbc.fanEnabled", "false");
            ods.setConnectionProperties(connectionProperties);
            return ods.getConnection();
        } catch (SQLException e) {
            logger.log(FINE, "SQL Exception thrown in connect()", e);
            throw new RuntimeException(e);
        }
    }

    private static Connection PDSconnect(String un, String pw, String cs) throws RuntimeException, Error {
        try {
            PoolDataSource pds;
            pds = PoolDataSourceFactory.getPoolDataSource();
            pds.setConnectionFactoryClassName("oracle.jdbc.pool.OracleDataSource");
            pds.setUser(un);
            pds.setPassword(pw);
            pds.setURL(cs);
            Properties prop = new Properties();
            prop.setProperty("autoCommit", "false");
            prop.setProperty("oracle.jdbc.fanEnabled", "false");
            pds.setConnectionProperties(prop);
            return pds.getConnection();
        } catch (SQLException e) {
            logger.log(FINE, "SQL Exception thrown in connect()", e);
            throw new RuntimeException(e);
        }
    }


    public static Connection getConnection(Map<CommandLineOptions, Object> pclo) {
        Connection connection;
        if (pclo.get(CommandLineOptions.CONNECTION_TYPE) == ConnectionType.ODS) {
            connection = connect((String) pclo.get(CommandLineOptions.USERNAME),
                    (String) pclo.get(CommandLineOptions.PASSWORD),
                    String.format("jdbc:oracle:%s:@%s", pclo.get(CommandLineOptions.DRIVER_TYPE).toString(), pclo.get(CommandLineOptions.CONNECT_STRING)));
        } else {
            connection = PDSconnect((String) pclo.get(CommandLineOptions.USERNAME),
                    (String) pclo.get(CommandLineOptions.PASSWORD),
                    String.format("jdbc:oracle:%s:@%s", pclo.get(CommandLineOptions.DRIVER_TYPE).toString(), pclo.get(CommandLineOptions.CONNECT_STRING)));
        }

        return connection;
    }

    private static List<Object[]> connectBenchmark(Map<CommandLineOptions, Object> pclo) throws Exception {
        logger.fine("Started creating connections");
        List<Callable<Object[]>> connectTests = new ArrayList<>();
        logger.fine("Creating threads");
        for (int i = 0; i < (Integer) pclo.get(CommandLineOptions.THREAD_COUNT); i++) {
            Callable<Object[]> connectTask = () -> {
                long start = System.currentTimeMillis();
                return new Object[]{getConnection(pclo), System.currentTimeMillis() - start, 0};
            };
            connectTests.add(connectTask);
        }
        logger.fine("Created threads");
        ExecutorService executor = Executors.newWorkStealingPool();
        List<Object[]> connectResults;
        logger.fine("Asking Threads to connect");
        connectResults = executor.invokeAll(connectTests).stream().map(future -> {
            try {
                return future.get();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).collect(Collectors.toList());
        return connectResults;
    }

    public static void main(String[] args) {
        try {
            logger.fine("Starting...");
            Map<CommandLineOptions, Object> pclo = parseCommandLine(args);
            Properties properties = System.getProperties();
            properties.entrySet()
                    .stream()
                    .filter(k -> (k.getKey().toString().startsWith("oracle.net")
                            || k.getKey().toString().startsWith("javax.net")))
                    .forEach(k -> System.out.println(String.format("%35s -> %s", k.getKey(), k.getValue())));

            long startMillis = System.currentTimeMillis();
            List<Object[]> connectResults = connectBenchmark(pclo);
            OptionalDouble avgConnectTime = connectResults.stream().mapToLong(r -> (Long) r[1]).average();
            long connectionTime = System.currentTimeMillis() - startMillis;
            System.out.println(String.format("Using Oracle Driver version %s, Built on %s", OracleDriver.getDriverVersion(), OracleDriver.getBuildDate()));
            System.out.println(String.format("Connecting using a %s driver", pclo.get(CommandLineOptions.DRIVER_TYPE)));
            System.out.println(String.format("Connected all the threads, Average connect time = %.2fms, Total time to connect all threads = %dms", avgConnectTime.orElse(0), connectionTime));
            logger.fine("Finished...");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Unexpected Exception thrown and not handled : ", e);
        }
    }


    private static Map<CommandLineOptions, Object> parseCommandLine(String[] arguments) {

        Map<CommandLineOptions, Object> parsedOptions = new HashMap<>();

        Options options = new Options();
        Option option8 = new Option("u", "username");
        option8.setRequired(true);
        option8.setArgName("username");
        option8.setArgs(1);
        Option option9 = new Option("p", "password");
        option9.setArgs(1);
        option9.setRequired(true);
        option9.setArgName("password");
        Option option10 = new Option("cs", "connect string");
        option10.setArgs(1);
        option10.setRequired(true);
        option10.setArgName("connectstring");
        Option option13 = new Option("ct", "pds or ods");
        option13.setArgs(1);
        option13.setArgName("threadcount");
        Option option14 = new Option("tc", "thread count (connections), defaults to 1");
        option14.setArgs(1);
        option14.setArgName("threadcount");
        Option option26 = new Option("cf", "credentials file in zip format");
        option26.setArgs(1);
        option26.setArgName("zipfile");
        Option option27 = new Option("dt", "Driver Type [thin,oci]");
        option27.setArgs(1);
        option27.setArgName("driver_type");
        Option option28 = new Option("h", "print help messages");

        Option option30 = new Option("debug", "turn on debugging. Written to standard out");

        OptionGroup helpGroup = new OptionGroup();
        OptionGroup commandGroup = new OptionGroup();

        helpGroup.addOption(option28);
        commandGroup.addOption(option8).addOption(option9).addOption(option10).addOption(option30).
                addOption(option14).addOption(option13).
                addOption(option26).addOption(option27);
        options.addOptionGroup(helpGroup).addOptionGroup(commandGroup);

        CommandLineParser clp = new DefaultParser();
        CommandLine cl;
        try {
            cl = clp.parse(options, arguments);
            if (cl.hasOption("debug")) {
                try {
                    System.setProperty("java.util.logging.config.class", "com.dom.LoggerConfig");
                    LogManager.getLogManager().readConfiguration();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if (cl.hasOption("h")) {
                HelpFormatter formatter = new HelpFormatter();
                formatter.printHelp("parameters:", options);
                System.exit(0);
            }
            if (cl.hasOption("u")) {
                parsedOptions.put(CommandLineOptions.USERNAME, cl.getOptionValue("u"));
            }
            if (cl.hasOption("p")) {
                parsedOptions.put(CommandLineOptions.PASSWORD, cl.getOptionValue("p"));
            }
            if (cl.hasOption("cs")) {
                parsedOptions.put(CommandLineOptions.CONNECT_STRING, cl.getOptionValue("cs"));
            }
            if (cl.hasOption("tc")) {
                parsedOptions.put(CommandLineOptions.THREAD_COUNT, Integer.parseInt(cl.getOptionValue("tc")));
            } else {
                parsedOptions.put(CommandLineOptions.THREAD_COUNT, 1);
            }

            if (cl.hasOption("dt")) {
                parsedOptions.put(CommandLineOptions.THREAD_COUNT, Integer.parseInt(cl.getOptionValue("tc")));
            } else {
                parsedOptions.put(CommandLineOptions.THREAD_COUNT, 1);
            }

            parsedOptions.put(CommandLineOptions.CONNECTION_TYPE, ConnectionType.ODS);
            if (cl.hasOption("ct")) {
                if (cl.getOptionValue("ct").equals("pds")) {
                    parsedOptions.put(CommandLineOptions.CONNECTION_TYPE, ConnectionType.PDS);
                }
            }
            if (cl.hasOption("cf")) {
                if ((new File(cl.getOptionValue("cf")).exists())) {
                    setupSecureOracleCloudProperties("DummyPassw0rd!", cl.getOptionValue("cf"), true);
                } else {
                    System.err.printf("The credentials file %s does not exists. Please specify a valid path and retry.\n", cl.getOptionValue("cf"));
                    System.exit(-1);
                }
            }
            parsedOptions.put(CommandLineOptions.DRIVER_TYPE, DriverType.thin);
            if (cl.hasOption("dt")) {
                try {
                    DriverType dt = DriverType.valueOf(cl.getOptionValue("dt"));
                    parsedOptions.put(CommandLineOptions.DRIVER_TYPE, dt);
                } catch (IllegalArgumentException e) {
                    throw new ParserException("Driver Type must be \"oci\" or \"thin\"");
                }
            }

        } catch (ParseException pe) {
            System.out.println("ERROR : " + pe.getMessage());
            HelpFormatter formatter = new HelpFormatter();
            formatter.printHelp("parameters:", options);
            System.exit(-1);
        }
        return parsedOptions;

    }

    public static Path setupSecureOracleCloudProperties(String passwd, String credentialsLocation, Boolean deleteOnExit) throws RuntimeException {
//        Creates temp directory containing key, sqlnet.ora and tnsnames.ora to enable connection to the cloud.
        try {
            if (!testJCE()) {
                throw new RuntimeException("Extended JCE support is not installed.");
            }
            Path tmp = Files.createTempDirectory("oracle_cloud_config");
            Path origfile = Paths.get(credentialsLocation);
            if (deleteOnExit) {
                Runtime.getRuntime().addShutdownHook(new Thread(() -> recursiveDelete(tmp)));
            }

            Path pzip = tmp.resolve("temp.zip");
            Files.copy(origfile, pzip);

            ZipFile zf = new ZipFile(pzip.toFile());
            Enumeration<? extends ZipEntry> entities = zf.entries();
            while (entities.hasMoreElements()) {
                ZipEntry entry = entities.nextElement();
                String name = entry.getName();
                Path p = tmp.resolve(name);
                Files.copy(zf.getInputStream(entry), p);
            }

            String pathToWallet = tmp.toFile().getAbsolutePath();

            System.setProperty("oracle.net.tns_admin", pathToWallet);
            System.setProperty("oracle.net.ssl_server_dn_match", "true");
            System.setProperty("oracle.net.ssl_version", "1.2");

            // open the CA's wallet
            OracleWallet caWallet = new OracleWallet();
            caWallet.open(pathToWallet, null);


            char[] keyAndTrustStorePasswd = OraclePKIGenFunc.getCreatePassword(passwd, false);

            // certs
            OracleWallet jksK = caWallet.migratePKCS12toJKS(keyAndTrustStorePasswd, OracleWallet.MIGRATE_KEY_ENTIRES_ONLY);
            // migrate (trusted) cert entries from p12 to different jks store
            OracleWallet jksT = caWallet.migratePKCS12toJKS(keyAndTrustStorePasswd, OracleWallet.MIGRATE_TRUSTED_ENTRIES_ONLY);
            String trustPath = pathToWallet + "/sqlclTrustStore.jks";
            String keyPath = pathToWallet + "/sqlclKeyStore.jks";

            jksT.saveAs(trustPath);
            jksK.saveAs(keyPath);


            System.setProperty("javax.net.ssl.trustStore", trustPath);
            System.setProperty("javax.net.ssl.trustStorePassword", passwd);
            System.setProperty("javax.net.ssl.keyStore", keyPath);
            System.setProperty("javax.net.ssl.keyStorePassword", passwd);
            java.security.Security.addProvider(new oracle.security.pki.OraclePKIProvider());
            return tmp;

        } catch (IOException e) {
            logger.fine(String.format("Unable to open and process the credentials file %s.", credentialsLocation));
            throw new RuntimeException(e);
        }
    }

    private static boolean testJCE() {
        int maxKeySize = 0;
        try {
            maxKeySize = Cipher.getMaxAllowedKeyLength("AES");

        } catch (NoSuchAlgorithmException ignore) {
        }
        return maxKeySize > 128;
    }

    public static void recursiveDelete(final Path path) {

        try {
            Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, @SuppressWarnings("unused") BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
                    if (e == null) {
                        Files.delete(dir);
                        return FileVisitResult.CONTINUE;
                    }
                    // directory iteration failed
                    throw e;
                }
            });
            logger.fine(String.format("Deleted tmp directory : %s", path.toString()));
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete " + path, e);
        }
    }


}


