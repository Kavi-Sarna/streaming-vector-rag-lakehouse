package com.sarna.rag.flink;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FSInputStream;
import org.apache.hadoop.fs.FileAlreadyExistsException;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.util.Progressable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * Hadoop filesystem for the scheme {@code s3} on the local Silo bucket. The Hadoop catalog creates
 * namespace directories through this client. Table files go through Iceberg's S3 file IO.
 */
public final class SiloFileSystem extends FileSystem {
  static final String IMPL = "fs.s3.impl";
  static final String DISABLE_CACHE = "fs.s3.impl.disable.cache";
  static final String ENDPOINT = "fs.s3.endpoint";
  static final String ACCESS_KEY = "fs.s3.access.key";
  static final String SECRET_KEY = "fs.s3.secret.key";
  static final String REGION = "fs.s3.region";

  private URI uri;
  private S3Client s3;
  private Path workingDirectory;

  public SiloFileSystem() {}

  static String bucket(Path path) {
    String host = path.toUri().getHost();
    if (host == null || host.isEmpty()) {
      throw new IllegalArgumentException("s3 path needs a bucket: " + path);
    }
    return host;
  }

  static String key(Path path) {
    String raw = path.toUri().getPath();
    if (raw == null || raw.isEmpty() || "/".equals(raw)) {
      return "";
    }
    return raw.startsWith("/") ? raw.substring(1) : raw;
  }

  static FSDataInputStream input(byte[] bytes) throws IOException {
    return new FSDataInputStream(new SiloBytesInput(bytes));
  }

  static String directoryKey(String key) {
    if (key.isEmpty() || key.endsWith("/")) {
      return key;
    }
    return key + "/";
  }

  @Override
  public String getScheme() {
    return "s3";
  }

  @Override
  public URI getUri() {
    return uri;
  }

  @Override
  public void initialize(URI name, Configuration conf) throws IOException {
    super.initialize(name, conf);
    uri = name;
    String endpoint = conf.get(ENDPOINT);
    String accessKey = conf.get(ACCESS_KEY);
    String secretKey = conf.get(SECRET_KEY);
    if (endpoint == null || accessKey == null || secretKey == null) {
      throw new IOException("fs.s3.endpoint, fs.s3.access.key, and fs.s3.secret.key are required");
    }
    s3 =
        S3Client.builder()
            .region(Region.of(conf.get(REGION, "us-east-1")))
            .endpointOverride(URI.create(endpoint))
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
            .forcePathStyle(true)
            .httpClient(UrlConnectionHttpClient.create())
            .build();
    String host = name.getHost() == null ? "" : name.getHost();
    workingDirectory = new Path("s3://" + host + "/");
  }

  @Override
  public FSDataInputStream open(Path path, int bufferSize) throws IOException {
    String bucket = bucket(path);
    String key = key(path);
    try {
      byte[] bytes =
          s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray();
      return input(bytes);
    } catch (S3Exception exception) {
      if (exception.statusCode() == 404) {
        throw new FileNotFoundException("No such object: " + path);
      }
      throw new IOException(exception);
    } catch (SdkException exception) {
      throw new IOException(exception);
    }
  }

  @Override
  public FSDataOutputStream create(
      Path path,
      FsPermission permission,
      boolean overwrite,
      int bufferSize,
      short replication,
      long blockSize,
      Progressable progress)
      throws IOException {
    String bucket = bucket(path);
    String key = key(path);
    if (!overwrite && exists(bucket, key)) {
      throw new FileAlreadyExistsException("Object already exists: " + path);
    }
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    OutputStream uploading =
        new OutputStream() {
          @Override
          public void write(int value) {
            buffer.write(value);
          }

          @Override
          public void write(byte[] values, int offset, int length) {
            buffer.write(values, offset, length);
          }

          @Override
          public void close() throws IOException {
            putBytes(bucket, key, buffer.toByteArray());
          }
        };
    return new FSDataOutputStream(uploading, statistics);
  }

  @Override
  public FSDataOutputStream append(Path path, int bufferSize, Progressable progress) {
    throw new UnsupportedOperationException("SiloFileSystem does not append objects");
  }

  @Override
  public boolean rename(Path source, Path destination) throws IOException {
    String bucket = bucket(source);
    if (!bucket.equals(bucket(destination))) {
      throw new IOException("Cross-bucket rename is not supported: " + source + " -> " + destination);
    }
    try {
      s3.copyObject(
          CopyObjectRequest.builder()
              .sourceBucket(bucket)
              .sourceKey(key(source))
              .destinationBucket(bucket)
              .destinationKey(key(destination))
              .build());
      s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key(source)).build());
      return true;
    } catch (SdkException exception) {
      throw new IOException(exception);
    }
  }

  @Override
  public boolean delete(Path path, boolean recursive) throws IOException {
    String bucket = bucket(path);
    String key = key(path);
    String prefix = key.isEmpty() ? "" : key;
    List<ObjectIdentifier> pending = new ArrayList<>();
    String token = null;
    boolean found = false;
    do {
      ListObjectsV2Response page = list(bucket, prefix, null, token);
      for (S3Object object : page.contents()) {
        if (!recursive && !object.key().equals(key) && !object.key().equals(directoryKey(key))) {
          throw new IOException("Directory " + path + " is not empty");
        }
        found = true;
        pending.add(ObjectIdentifier.builder().key(object.key()).build());
        if (pending.size() == 1000) {
          deleteBatch(bucket, pending);
        }
      }
      token = page.isTruncated() ? page.nextContinuationToken() : null;
    } while (token != null);
    deleteBatch(bucket, pending);
    return found;
  }

  @Override
  public FileStatus[] listStatus(Path path) throws FileNotFoundException, IOException {
    FileStatus self = getFileStatus(path);
    if (!self.isDirectory()) {
      return new FileStatus[] {self};
    }
    String bucket = bucket(path);
    String prefix = directoryKey(key(path));
    List<FileStatus> children = new ArrayList<>();
    String token = null;
    do {
      ListObjectsV2Response page = list(bucket, prefix, "/", token);
      for (CommonPrefix common : page.commonPrefixes()) {
        String name = childName(prefix, common.prefix());
        if (!name.isEmpty()) {
          children.add(status(new Path(path, name), 0, true));
        }
      }
      for (S3Object object : page.contents()) {
        String name = childName(prefix, object.key());
        if (name.isEmpty()) {
          continue;
        }
        children.add(status(new Path(path, name), object.size(), false));
      }
      token = page.isTruncated() ? page.nextContinuationToken() : null;
    } while (token != null);
    return children.toArray(FileStatus[]::new);
  }

  @Override
  public void setWorkingDirectory(Path directory) {
    workingDirectory = directory;
  }

  @Override
  public Path getWorkingDirectory() {
    return workingDirectory;
  }

  @Override
  public boolean mkdirs(Path path, FsPermission permission) throws IOException {
    String key = key(path);
    if (!key.isEmpty()) {
      putEmpty(bucket(path), directoryKey(key));
    }
    return true;
  }

  @Override
  public FileStatus getFileStatus(Path path) throws IOException {
    String bucket = bucket(path);
    String key = key(path);
    if (key.isEmpty() || exists(bucket, directoryKey(key)) || prefixExists(bucket, directoryKey(key))) {
      return status(path, 0, true);
    }
    if (exists(bucket, key)) {
      return status(path, 0, false);
    }
    throw new FileNotFoundException("No such object: " + path);
  }

  @Override
  public void close() throws IOException {
    if (s3 != null) {
      s3.close();
    }
    super.close();
  }

  private ListObjectsV2Response list(String bucket, String prefix, String delimiter, String token)
      throws IOException {
    try {
      ListObjectsV2Request.Builder request =
          ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).continuationToken(token);
      if (delimiter != null) {
        request.delimiter(delimiter);
      }
      return s3.listObjectsV2(request.build());
    } catch (SdkException exception) {
      throw new IOException(exception);
    }
  }

  private boolean exists(String bucket, String key) throws IOException {
    if (key.isEmpty()) {
      return true;
    }
    try {
      s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
      return true;
    } catch (S3Exception exception) {
      if (exception.statusCode() == 404) {
        return false;
      }
      throw new IOException(exception);
    } catch (SdkException exception) {
      throw new IOException(exception);
    }
  }

  private boolean prefixExists(String bucket, String prefix) throws IOException {
    ListObjectsV2Response page = list(bucket, prefix, null, null);
    return !page.contents().isEmpty();
  }

  private void putEmpty(String bucket, String key) throws IOException {
    putBytes(bucket, key, new byte[0]);
  }

  private void putBytes(String bucket, String key, byte[] bytes) throws IOException {
    try {
      s3.putObject(
          PutObjectRequest.builder().bucket(bucket).key(key).build(),
          software.amazon.awssdk.core.sync.RequestBody.fromBytes(bytes));
    } catch (SdkException exception) {
      throw new IOException(exception);
    }
  }

  private void deleteBatch(String bucket, List<ObjectIdentifier> pending) throws IOException {
    if (pending.isEmpty()) {
      return;
    }
    try {
      s3.deleteObjects(
          DeleteObjectsRequest.builder()
              .bucket(bucket)
              .delete(Delete.builder().objects(pending).build())
              .build());
      pending.clear();
    } catch (SdkException exception) {
      throw new IOException(exception);
    }
  }

  private static String childName(String prefix, String full) {
    if (!full.startsWith(prefix)) {
      return "";
    }
    String rest = full.substring(prefix.length());
    int slash = rest.indexOf('/');
    if (slash >= 0) {
      rest = rest.substring(0, slash);
    }
    return rest;
  }

  private static final class SiloBytesInput extends FSInputStream {
    private final byte[] bytes;
    private int position;

    private SiloBytesInput(byte[] bytes) {
      this.bytes = bytes;
    }

    @Override
    public int read() {
      if (position >= bytes.length) {
        return -1;
      }
      return bytes[position++] & 0xff;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) {
      if (position >= bytes.length) {
        return -1;
      }
      int count = Math.min(length, bytes.length - position);
      System.arraycopy(bytes, position, buffer, offset, count);
      position += count;
      return count;
    }

    @Override
    public void seek(long target) throws IOException {
      if (target < 0 || target > bytes.length) {
        throw new IOException("seek " + target + " is outside 0.." + bytes.length);
      }
      position = (int) target;
    }

    @Override
    public long getPos() {
      return position;
    }

    @Override
    public boolean seekToNewSource(long target) {
      return false;
    }
  }

  private static FileStatus status(Path path, long length, boolean directory) {
    return new FileStatus(length, directory, 1, 128L * 1024 * 1024, System.currentTimeMillis(), path);
  }
}
