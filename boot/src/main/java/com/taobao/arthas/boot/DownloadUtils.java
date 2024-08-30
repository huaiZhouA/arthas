package com.taobao.arthas.boot;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.*;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.taobao.arthas.common.AnsiLog;
import com.taobao.arthas.common.IOUtils;

/**
 * @author hengyunabc 2018-11-06
 */
public class DownloadUtils {
    private static final String ARTHAS_VERSIONS_URL = "https://arthas.aliyun.com/api/versions";
    private static final String ARTHAS_LATEST_VERSIONS_URL = "https://arthas.aliyun.com/api/latest_version";

    private static final String ARTHAS_DOWNLOAD_URL = "https://arthas.aliyun.com/download/${VERSION}?mirror=${REPO}";

    private static final int CONNECTION_TIMEOUT = 3000;

    private static final String INNER_REPO_HOST = "n-pre-sino-mock-service.meetsocial.cn";

    private static final String COMPANY_REPO_HOST = "pre-sino-mock-service.meetsocial.cn";

    private static final String ARTHAS_CORE_LOCAL_PATH = "/home/work/.arthas/lib/${VERSION}/arthas/arthas-core.jar";

    private static final List<String> REPO_LIST = Arrays.asList(INNER_REPO_HOST, COMPANY_REPO_HOST);

    public static String readLatestReleaseVersion() {
        InputStream inputStream = null;
        try {
            URLConnection connection = openURLConnection(ARTHAS_LATEST_VERSIONS_URL);
            inputStream = connection.getInputStream();
            return IOUtils.toString(inputStream).trim();
        } catch (Throwable t) {
            AnsiLog.error("Can not read arthas version from: " + ARTHAS_LATEST_VERSIONS_URL);
            AnsiLog.debug(t);
        } finally {
            IOUtils.close(inputStream);
        }
        return null;
    }

    public static List<String> readRemoteVersions() {
        InputStream inputStream = null;
        try {
            URLConnection connection = openURLConnection(ARTHAS_VERSIONS_URL);
            inputStream = connection.getInputStream();
            String versionsStr = IOUtils.toString(inputStream);
            String[] versions = versionsStr.split("\r\n");

            ArrayList<String> result = new ArrayList<String>();
            for (String version : versions) {
                result.add(version.trim());
            }
            return result;

        } catch (Throwable t) {
            AnsiLog.error("Can not read arthas versions from: " + ARTHAS_VERSIONS_URL);
            AnsiLog.debug(t);
        } finally {
            IOUtils.close(inputStream);
        }
        return null;
    }

    private static String getRepoUrl(String repoUrl, boolean http) {
        if (repoUrl.endsWith("/")) {
            repoUrl = repoUrl.substring(0, repoUrl.length() - 1);
        }

        if (http && repoUrl.startsWith("https")) {
            repoUrl = "http" + repoUrl.substring("https".length());
        }
        return repoUrl;
    }

    private static String selectRepoHost() {
        for (String repo_host : REPO_LIST) {
            try {
                InetAddress inetAddress = InetAddress.getByName(repo_host);
                Socket socket = new Socket(inetAddress, CONNECTION_TIMEOUT);
                return repo_host;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return null;
    }

    private static void downloadArthasCore(String arthasVersion) throws IOException {
        String arthasCoreLocalPath = ARTHAS_CORE_LOCAL_PATH.replace("${VERSION}", arthasVersion);
        String repoHost = selectRepoHost();
        if (repoHost == null || repoHost.isEmpty()) {
            return;
        }
        String repoUrl = "https://" + repoHost + "/api/common/download/arthas-core.jar";
        saveUrl(arthasCoreLocalPath, repoUrl, true);
    }

    public static void downArthasPackaging(String repoMirror, boolean http, String arthasVersion, String savePath)
            throws IOException {
        String repoUrl = getRepoUrl(ARTHAS_DOWNLOAD_URL, http);

        File unzipDir = new File(savePath, arthasVersion + File.separator + "arthas");

        File tempFile = File.createTempFile("arthas", "arthas");

        AnsiLog.debug("Arthas download temp file: " + tempFile.getAbsolutePath());

        String remoteDownloadUrl = repoUrl.replace("${REPO}", repoMirror).replace("${VERSION}", arthasVersion);
        AnsiLog.info("Start download arthas from remote server: " + remoteDownloadUrl);
        saveUrl(tempFile.getAbsolutePath(), remoteDownloadUrl, true);
        AnsiLog.info("Download arthas success.");
        IOUtils.unzip(tempFile.getAbsolutePath(), unzipDir.getAbsolutePath());

        try {
            TimeUnit.MINUTES.sleep(1);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        downloadArthasCore(arthasVersion);
    }

    private static void saveUrl(final String filename, final String urlString, boolean printProgress)
            throws IOException {
        BufferedInputStream in = null;
        FileOutputStream fout = null;
        try {
            URLConnection connection = openURLConnection(urlString);
            in = new BufferedInputStream(connection.getInputStream());
            List<String> values = connection.getHeaderFields().get("Content-Length");
            int fileSize = 0;
            if (values != null && !values.isEmpty()) {
                String contentLength = values.get(0);
                if (contentLength != null) {
                    // parse the length into an integer...
                    fileSize = Integer.parseInt(contentLength);
                }
            }

            fout = new FileOutputStream(filename);

            final byte[] data = new byte[1024 * 1024];
            int totalCount = 0;
            int count;
            long lastPrintTime = System.currentTimeMillis();
            while ((count = in.read(data, 0, data.length)) != -1) {
                totalCount += count;
                if (printProgress) {
                    long now = System.currentTimeMillis();
                    if (now - lastPrintTime > 1000) {
                        AnsiLog.info("File size: {}, downloaded size: {}, downloading ...", formatFileSize(fileSize),
                                formatFileSize(totalCount));
                        lastPrintTime = now;
                    }
                }
                fout.write(data, 0, count);
            }
        } catch (javax.net.ssl.SSLException e) {
            AnsiLog.error("TLS connect error, please try to add --use-http argument.");
            AnsiLog.error("URL: " + urlString);
            AnsiLog.error(e);
        } finally {
            IOUtils.close(in);
            IOUtils.close(fout);
        }
    }

    /**
     * support redirect
     *
     * @param url
     * @return
     * @throws MalformedURLException
     * @throws IOException
     */
    private static URLConnection openURLConnection(String url) throws MalformedURLException, IOException {
        URLConnection connection = new URL(url).openConnection();
        if (connection instanceof HttpURLConnection) {
            connection.setConnectTimeout(CONNECTION_TIMEOUT);
            // normally, 3xx is redirect
            int status = ((HttpURLConnection) connection).getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                if (status == HttpURLConnection.HTTP_MOVED_TEMP || status == HttpURLConnection.HTTP_MOVED_PERM
                        || status == HttpURLConnection.HTTP_SEE_OTHER) {
                    String newUrl = connection.getHeaderField("Location");
                    AnsiLog.debug("Try to open url: {}, redirect to: {}", url, newUrl);
                    return openURLConnection(newUrl);
                }
            }
        }
        return connection;
    }

    private static String formatFileSize(long size) {
        String hrSize;

        double b = size;
        double k = size / 1024.0;
        double m = ((size / 1024.0) / 1024.0);
        double g = (((size / 1024.0) / 1024.0) / 1024.0);
        double t = ((((size / 1024.0) / 1024.0) / 1024.0) / 1024.0);

        DecimalFormat dec = new DecimalFormat("0.00");

        if (t > 1) {
            hrSize = dec.format(t).concat(" TB");
        } else if (g > 1) {
            hrSize = dec.format(g).concat(" GB");
        } else if (m > 1) {
            hrSize = dec.format(m).concat(" MB");
        } else if (k > 1) {
            hrSize = dec.format(k).concat(" KB");
        } else {
            hrSize = dec.format(b).concat(" Bytes");
        }

        return hrSize;
    }

}
