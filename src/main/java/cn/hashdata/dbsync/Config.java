package cn.hashdata.dbsync;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;

import org.apache.commons.configuration2.Configuration;
import org.apache.commons.configuration2.PropertiesConfiguration;
import org.apache.commons.configuration2.SubsetConfiguration;
import org.apache.commons.configuration2.builder.fluent.Configurations;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Configurations about dbsync.
 *
 * @author yuze
 *
 */
public class Config {
  private static final String DEFAULT_TABLEMAP_DIR = "etc/";

  private Logger logger = LogManager.getLogger("Dbsync." + Config.class);

  protected Configuration config;

  public String reporter;
  public int changeset_queue_size;
  public int transform_pool_size;
  public int trans_result_queue_size;
  public int row_cache_size;
  public int merge_pool_size;
  public int merge_interval;
  public int batch_size;
  public int loader_conn_size;
  public int loader_task_queue_size;
  public int bookkeeping_interval;
  public String bookkeeping_table;
  public int report_interval;

  public ConnectionConfig target;
  public ConnectionConfig bookkeeping;

  public ArrayList<String> dataSource;
  public ArrayList<String> dataSourceType;
  public ArrayList<MaxwellConfig> maxwellConf;
  public HashMap<String, String> tableMap;
  public int loadersCount;

  public static class ConnectionConfig {
    public String jdbcUrl;
    public String user;
    public String passwd;
  }

  public static class MaxwellConfig {
    public String name;
    public String server;
    public String topic;
    public HashMap<String, String> tableMap;
  }

  public Config() {
    config = new PropertiesConfiguration();
    getBasicConfig();
  }

  /**
   * Read config file and store in {@code Config}.
   *
   * @param configFile the config file.
   * @throws ConfigurationException - if an error occurred when loading the configuration
   * @throws DbsyncException - wrap and throw Exception which cannot be handled
   */
  public Config(String configFile) throws ConfigurationException, DbsyncException {
    Configurations configs = new Configurations();
    config = configs.properties(new File(configFile));

    getBasicConfig();
    getConnectionConfig();
    getDataSourceConfig();

    logConfig();
  }

  protected void getBasicConfig() {
    reporter = config.getString("metrics.reporter", "console");
    report_interval = config.getInt("metrics.reporter.console.interval", 15);

    transform_pool_size = config.getInt("transform.thread_pool.size", 10);
    changeset_queue_size = transform_pool_size * 2;
    trans_result_queue_size = transform_pool_size * 2;

    merge_pool_size = config.getInt("merge.thread_pool.size", 10);
    merge_interval = config.getInt("merge.interval", 10000);
    batch_size = config.getInt("merge.batch.size", 50000);
    row_cache_size = batch_size * 2;

    loader_conn_size = config.getInt("loader.conn_pool.size", 10);
    loader_task_queue_size = config.getInt("loader.task_queue.size", 2);

    bookkeeping_interval = config.getInt("bookkeeping.interval", 10000);
    bookkeeping_table = config.getString("bookkeeping.table_name", "bookkeeping");

    dataSource = new ArrayList<String>();
    dataSourceType = new ArrayList<String>();
    tableMap = new HashMap<String, String>();
    maxwellConf = new ArrayList<MaxwellConfig>();
  }

  protected void getConnectionConfig() throws DbsyncException {
    target = getConnConfig("target");
    if (target.jdbcUrl == null) {
      String message = "Please designate url for target Database.";
      logger.fatal(message);
      throw new DbsyncException(message);
    }

    bookkeeping = getConnConfig("bookkeeping");
    if (bookkeeping.jdbcUrl == null) {
      bookkeeping = target;
    }
  }

  protected void getDataSourceConfig() throws DbsyncException {
    String[] sources = config.getString("data_source").replaceAll("[ \f\n\r\t]", "").split(",");
    if (sources == null || sources.length == 0) {
      String message = "Please designate at least one data source.";
      logger.fatal(message);
      throw new DbsyncException(message);
    }
    for (int i = 0; i < sources.length; i++) {
      dataSource.add(sources[i]);
    }

    fetchProviderAndTableMap();
  }

  /**
   * Get the {@code Provider} configuration.
   *
   * @throws DbsyncException - wrap and throw Exception which cannot be handled
   */
  protected void fetchProviderAndTableMap() throws DbsyncException {
    loadersCount = 0;

    for (int i = 0; i < dataSource.size(); i++) {
      String name = dataSource.get(i);
      String type = config.getString(name + ".type");

      dataSourceType.add(config.getString(dataSource.get(i) + ".type"));

      switch (type) {
        case "maxwell":
          MaxwellConfig conf = fetchMaxwellProviderConfig(name);
          conf.tableMap = fetchTableMap(conf.name);
          maxwellConf.add(conf);
          break;
        default:
          String message = "Unrecognized type for data source " + name;
          logger.fatal(message);
          throw new DbsyncException(message);
      }
    }

    if (loader_conn_size > loadersCount) {
      loader_conn_size = loadersCount;
    }
  }

  /**
   * Get the connection configuration to database.
   *
   * @param prefix "target" or "bookkeeping" database
   * @return {@code ConnectionConfig} to database.
   */
  protected ConnectionConfig getConnConfig(String prefix) {
    Configuration subConfig = new SubsetConfiguration(config, prefix, ".");
    ConnectionConfig connectionConfig = new ConnectionConfig();

    connectionConfig.jdbcUrl = subConfig.getString("url");
    connectionConfig.user = subConfig.getString("user");
    connectionConfig.passwd = subConfig.getString("passwd");

    return connectionConfig;
  }

  /**
   * Get {@code MaxwellProvider} configuration.
   *
   * @param prefix the Provider's name
   * @return {@code MaxwellConfig} for {@code MaxwellProvider}
   * @throws DbsyncException - wrap and throw Exception which cannot be handled
   */
  protected MaxwellConfig fetchMaxwellProviderConfig(String prefix) throws DbsyncException {
    Configuration subConfig = new SubsetConfiguration(config, prefix, ".");
    MaxwellConfig maxwellConf = new MaxwellConfig();

    maxwellConf.name = prefix;
    maxwellConf.server = subConfig.getString("kafka.server");
    maxwellConf.topic = subConfig.getString("kafka.topic");

    if (maxwellConf.server == null) {
      String message = "Please designate server for " + prefix + ".";
      logger.fatal(message);
      throw new DbsyncException(message);
    }

    if (maxwellConf.topic == null) {
      String message = "Please designate topic for " + prefix + ".";
      logger.fatal(message);
      throw new DbsyncException(message);
    }

    return maxwellConf;
  }

  private HashMap<String, String> fetchTableMap(String dataSource) throws DbsyncException {
    Configurations configs = new Configurations();
    Configuration tableConfig = null;

    try {
      tableConfig = configs.properties(new File(DEFAULT_TABLEMAP_DIR + dataSource + ".properties"));
    } catch (ConfigurationException e) {
      throw new DbsyncException(e);
    }

    String originTable, mappedTable;
    HashMap<String, String> localTableMap = new HashMap<String, String>();
    Iterator<String> tables = tableConfig.getKeys();

    while (tables.hasNext()) {
      originTable = tables.next();
      mappedTable = tableConfig.getString(originTable);

      if (originTable.split("\\.").length != 2 || mappedTable.split("\\.").length != 2) {
        String message = "Wrong format: " + originTable + ", " + mappedTable;
        logger.fatal(message);
        throw new DbsyncException(message);
      }

      localTableMap.put(dataSource + "." + originTable, mappedTable);

      if (!tableMap.values().contains(mappedTable)) {
        loadersCount++;
      }
      tableMap.put(dataSource + "." + originTable, mappedTable);
    }

    return localTableMap;
  }

  /**
   * Print log about dbsync configuration.
   */
  public void logConfig() {
    String config = "Configures: "
        + "\n\tchangeSet queue size = " + changeset_queue_size + "\n\ttransform thread pool size = "
        + transform_pool_size + "\n\ttransform result queue size = " + trans_result_queue_size
        + "\n\trow cache size = " + row_cache_size + "\n\tmerge thread pool size = "
        + merge_pool_size + "\n\tmerge interval = " + merge_interval
        + "\n\tbatch size = " + batch_size + "\n\tloader conn size = " + loader_conn_size
        + "\n\tloader task queue size = " + loader_task_queue_size + "\n\tbookkeeping interval = "
        + bookkeeping_interval + "\n\tbookkeeping table = " + bookkeeping_table
        + "\n\treport interval = " + report_interval;
    logger.info(config);

    StringBuilder sb = new StringBuilder();
    sb.append("Data Source: \n");

    for (int i = 0, len = dataSource.size(); i < len; i++) {
      sb.append("\tType: " + dataSourceType.get(i) + " Name: " + dataSource.get(i) + "\n");
    }

    logger.info(sb.toString());
  }
}
