package com.queryio.sftp.migrate;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketException;
import java.sql.Connection;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.io.IOUtils;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.FileSystemOptions;
import org.apache.commons.vfs2.UserAuthenticator;
import org.apache.commons.vfs2.auth.StaticUserAuthenticator;
import org.apache.commons.vfs2.cache.SoftRefFilesCache;
import org.apache.commons.vfs2.impl.DefaultFileSystemConfigBuilder;
import org.apache.commons.vfs2.impl.DefaultFileSystemManager;
import org.apache.commons.vfs2.provider.sftp.SftpFileObject;
import org.apache.commons.vfs2.provider.sftp.SftpFileProvider;
import org.apache.commons.vfs2.provider.sftp.SftpFileSystemConfigBuilder;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.DFSInputStream;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.security.UserGroupInformation;

import com.queryio.common.EnvironmentalConstants;
import com.queryio.common.QueryIOConstants;
import com.queryio.common.database.CoreDBManager;
import com.queryio.common.util.AppLogger;
import com.queryio.core.bean.MigrationInfo;
import com.queryio.core.bean.User;
import com.queryio.core.dao.MigrationInfoDAO;
import com.queryio.core.dao.UserDAO;
import com.queryio.core.utils.QIODFSUtils;
import com.queryio.stream.util.QIODFSInputStream;

public class SFTPExportMigrationThread extends Thread {
	private int unitCount = 10;
	private MigrationInfo migrationInfo;

	private FileSystem dfs;

	boolean flag;

	boolean overwrite;
	String fsDefaultName;

	private String destinationPath;

	private int sftpPort;
	private String sftpHost;
	private String username;
	private String password;

	Configuration conf = null;

	FileSystemOptions fileSystemOptions;
	DefaultFileSystemManager manager;

	String rootURI = null;

	public SFTPExportMigrationThread(String user, String group, MigrationInfo migrationInfo, String userName,
			String password, Configuration conf, boolean overwrite) throws Exception {
		super(user);
		Thread.currentThread().setName(user);

		this.migrationInfo = migrationInfo;

		String ftpHost = migrationInfo.getDestinationPath().split("/")[2];
		String ftpPort = String.valueOf(FTP.DEFAULT_PORT);
		if (ftpHost.contains(":")) {
			String[] arr = ftpHost.split(":");
			ftpHost = arr[0];
			ftpPort = arr[1];
		}

		String[] array = migrationInfo.getDestinationPath().split("/");
		String sourcePath = "";
		for (int i = 3; i < array.length; i++) {
			sourcePath += "/" + array[i];
		}

		this.sftpPort = Integer.parseInt(ftpPort);
		this.sftpHost = ftpHost;
		this.username = userName;
		this.password = password;

		rootURI = "sftp://" + username + "@" + sftpHost + ":" + sftpPort;

		sourcePath = rootURI + sourcePath;
		destinationPath = sourcePath;

		if (AppLogger.getLogger().isDebugEnabled())
			AppLogger.getLogger().debug("SFTP export request received");

		this.connect();

		if (AppLogger.getLogger().isDebugEnabled())
			AppLogger.getLogger().debug("Connected to SFTP server");

		if (EnvironmentalConstants.isUseKerberos()) {
			Connection connection = null;
			try {
				connection = CoreDBManager.getQueryIODBConnection();

				User us = UserDAO.getUserDetail(connection, user);

				UserGroupInformation.setConfiguration(conf);
				// UserGroupInformation.getLoginUser(us.getUserName(),
				// SecurityHandler.decryptData(us.getPassword()));

				dfs = FileSystem.get(conf);
				dfs.setConf(conf);
			} finally {
				try {
					CoreDBManager.closeConnection(connection);
				} catch (Exception e) {
					AppLogger.getLogger().fatal(e.getMessage(), e);
				}
			}
		} else {
			dfs = QIODFSUtils.getFileSystemAs(user, group, conf);
		}

		this.overwrite = overwrite;
	}

	public void connect() throws FileSystemException, SocketException, IOException {
		fileSystemOptions = new FileSystemOptions();

		UserAuthenticator auth = new StaticUserAuthenticator(sftpHost, username, password);
		DefaultFileSystemConfigBuilder.getInstance().setUserAuthenticator(fileSystemOptions, auth);
		SftpFileSystemConfigBuilder.getInstance().setStrictHostKeyChecking(fileSystemOptions, "no");
		SftpFileSystemConfigBuilder.getInstance().setUserDirIsRoot(fileSystemOptions, false);

		SftpFileProvider provider = new SftpFileProvider();

		manager = new DefaultFileSystemManager();

		manager.addProvider("sftp", provider);

		manager.setFilesCache(new SoftRefFilesCache());

		manager.init();
	}

	@Override
	public void interrupt() {

		flag = false;

		Connection connection = null;
		try {
			connection = CoreDBManager.getQueryIODBConnection();
			migrationInfo.setStatus(QueryIOConstants.PROCESS_STATUS_STOPPED);
			migrationInfo.setEndTime(new Timestamp(System.currentTimeMillis()));
			MigrationInfoDAO.update(connection, migrationInfo);
		} catch (Exception e) {
			AppLogger.getLogger().fatal(e.getMessage(), e);
		} finally {
			try {
				CoreDBManager.closeConnection(connection);
			} catch (Exception e) {
				AppLogger.getLogger().fatal("Error closing database connection.", e);
			}
		}

		super.interrupt();

		try {
			if (dfs != null)
				dfs.close();
		} catch (IOException e) {
			AppLogger.getLogger().fatal(e.getMessage(), e);
		}
		try {
			if (manager != null)
				manager.close();
		} catch (Exception e) {
			AppLogger.getLogger().fatal(e.getMessage(), e);
		}
	}

	@Override
	public void run() {
		flag = true;
		Connection connection = null;
		try {
			if (AppLogger.getLogger().isDebugEnabled())
				AppLogger.getLogger().debug("Listing file paths");
			List filePaths = getAllFilePaths(migrationInfo.getSourcePath());
			if (AppLogger.getLogger().isDebugEnabled())
				AppLogger.getLogger().debug("size of folder " + migrationInfo.getSourcePath() + " " + filePaths.size());
			unitCount = 5 / 100 * filePaths.size();
			if (unitCount == 0)
				unitCount = 1;

			String pathPrefix = migrationInfo.getDestinationPath().replace("ftp://", "");
			pathPrefix = pathPrefix.substring(pathPrefix.indexOf("/"));
			pathPrefix = pathPrefix.trim();
			if (pathPrefix.endsWith("/")) {
				pathPrefix = pathPrefix.substring(0, pathPrefix.length() - 1);
			}

			int connectRetry = 0;
			int successCount = 0;
			int failCount = 0;

			if (AppLogger.getLogger().isDebugEnabled())
				AppLogger.getLogger().debug("Initiating export...");
			Path destPath;
			boolean success;
			for (int i = 0; i < filePaths.size(); i++) {
				if (flag) {
					if (i % unitCount == 0) {
						try {
							connection = CoreDBManager.getQueryIODBConnection();
							MigrationInfoDAO.update(connection, migrationInfo);
						} catch (Exception e) {
							AppLogger.getLogger().fatal(e.getMessage(), e);
						} finally {
							try {
								CoreDBManager.closeConnection(connection);
							} catch (Exception e) {
								AppLogger.getLogger().fatal("Error closing database connection.", e);
							}
						}
					}

					Path filePath = (Path) filePaths.get(i);
					InputStream inputStream = null;
					OutputStream outputStream = null;

					try {
						if (AppLogger.getLogger().isDebugEnabled())
							AppLogger.getLogger().debug("Fetching object: " + filePath);
						inputStream = getObject(filePath);

						String effectiveDestinationPath = migrationInfo.getSourcePath();

						if (effectiveDestinationPath.equals(filePath.toString())) {
							effectiveDestinationPath = "";
						} else {
							if (!effectiveDestinationPath.endsWith("/")) {
								effectiveDestinationPath += "/";
							}
							effectiveDestinationPath = filePath.toString().substring(effectiveDestinationPath.length());
						}

						if (effectiveDestinationPath.isEmpty()) {
							destPath = new Path(destinationPath + "/" + filePath.getName());
						} else {
							destPath = new Path(destinationPath + "/" + effectiveDestinationPath);
						}

						// destPath = new Path(destinationPath +
						// filePath.getName().substring(filePath.getName().lastIndexOf('/')
						// + 1));

						if (AppLogger.getLogger().isDebugEnabled())
							AppLogger.getLogger().debug("Saving data: " + destPath.toString());

						try {
							SftpFileObject obejct = (SftpFileObject) manager.resolveFile(destPath.toString(),
									fileSystemOptions);
							obejct.createFile();
							outputStream = obejct.getOutputStream();
						} catch (FileSystemException e) {
							if (connectRetry == 3)
								throw e;
							else {
								try {
									if (AppLogger.getLogger().isDebugEnabled())
										AppLogger.getLogger()
												.debug("IOException occurred. Reconnecting to SFTP server.");
									this.connect();
									if (manager.resolveFile(destPath.toString(), fileSystemOptions) == null
											|| !manager.resolveFile(destPath.toString(), fileSystemOptions).exists())
										throw e;
									if (AppLogger.getLogger().isDebugEnabled())
										AppLogger.getLogger().debug("Connection successful");
									connectRetry = 0;
								} catch (Exception ex) {
									Thread.sleep(1000);
									connectRetry++;
									AppLogger.getLogger().fatal(
											"Could not connect to SFTP server, exception: " + ex.getMessage(), ex);
								}
								i--;
								continue;
							}
						}

						try {
							IOUtils.copy(inputStream, outputStream);
						} catch (IOException e) {
							throw e;
						}

						if (AppLogger.getLogger().isDebugEnabled())
							AppLogger.getLogger().debug("Saving file: " + destPath.toString() + " successful");
						successCount++;
					} catch (Exception e) {
						if (connectRetry == 3)
							throw e;
						connectRetry = 0;
						AppLogger.getLogger().fatal(e.getMessage(), e);
						failCount++;
					} finally {
						if (outputStream != null) {
							try {
								outputStream.close();
							} catch (Exception e) {
								AppLogger.getLogger().fatal(e.getMessage(), e);
							}
						}
						if (inputStream != null) {
							try {
								inputStream.close();
							} catch (Exception e) {
								AppLogger.getLogger().fatal(e.getMessage(), e);
							}
						}
					}
					migrationInfo.setProgress((successCount * 100 / filePaths.size()));
					migrationInfo.setStatus("Migrated " + successCount + " of " + filePaths.size() + " objects."
							+ (failCount > 0 ? " Failed cases: " + failCount + "." : ""));
				}
			}
			migrationInfo.setEndTime(new Timestamp(System.currentTimeMillis()));
		} catch (Exception e) {
			AppLogger.getLogger().fatal("Error occured in migration.", e);
			migrationInfo.setEndTime(new Timestamp(System.currentTimeMillis()));
			migrationInfo.setStatus(QueryIOConstants.PROCESS_STATUS_FAILED);
		} finally {
			try {
				if (dfs != null)
					dfs.close();
			} catch (IOException e) {
				AppLogger.getLogger().fatal(e.getMessage(), e);
			}
			try {
				if (manager != null)
					manager.close();
			} catch (Exception e) {
				AppLogger.getLogger().fatal(e.getMessage(), e);
			}
		}
		if (flag) {
			try {
				connection = CoreDBManager.getQueryIODBConnection();
				MigrationInfoDAO.update(connection, migrationInfo);
			} catch (Exception e) {
				AppLogger.getLogger().fatal(e.getMessage(), e);
			} finally {
				try {
					CoreDBManager.closeConnection(connection);
				} catch (Exception e) {
					AppLogger.getLogger().fatal("Error closing database connection.", e);
				}
			}
		}
	}

	private List getAllFilePaths(String str) throws Exception {
		Path path = new Path(str);
		FileStatus stat = dfs.getFileStatus(path);
		List list = new ArrayList();
		if (!stat.isDirectory()) {
			list.add(path);
		} else {
			FileStatus[] stats = dfs.listStatus(path);
			for (int i = 0; i < stats.length; i++) {
				if (!str.endsWith("/"))
					str += "/";
				list.addAll(getAllFilePaths(str + stats[i].getPath().getName()));
			}
		}
		return list;
	}

	private BufferedInputStream getObject(Path path) throws Exception {
		DFSInputStream dfsInputStream = null;
		QIODFSInputStream qioInputStream = null;

		DistributedFileSystem fs = (DistributedFileSystem) dfs;
		dfsInputStream = (DFSInputStream) fs.getClient().open(path.toUri().toString());
		try {
			qioInputStream = new QIODFSInputStream(dfsInputStream, fs, path.toUri().toString());
		} catch (Exception e) {
			if (dfsInputStream != null) {
				dfsInputStream.close();
			}
			throw e;
		}
		return new BufferedInputStream(qioInputStream);
	}
}
