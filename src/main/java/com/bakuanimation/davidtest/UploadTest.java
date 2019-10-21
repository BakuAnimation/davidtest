package com.bakuanimation.davidtest;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.davfx.ninio.core.Address;
import com.davfx.ninio.core.Listener;
import com.davfx.ninio.core.Ninio;
import com.davfx.ninio.core.TcpSocketServer;
import com.davfx.ninio.http.HttpListening;
import com.davfx.ninio.http.HttpMethod;
import com.davfx.ninio.http.service.Annotated;
import com.davfx.ninio.http.service.HttpContentType;
import com.davfx.ninio.http.service.HttpController;
import com.davfx.ninio.http.service.HttpPost;
import com.davfx.ninio.http.service.HttpService;
import com.davfx.ninio.http.service.annotations.HeaderParameter;
import com.davfx.ninio.http.service.annotations.Path;
import com.davfx.ninio.http.service.annotations.QueryParameter;
import com.davfx.ninio.http.service.annotations.Route;
import com.davfx.ninio.http.service.controllers.ResourceAssets;
import com.davfx.ninio.util.Wait;
import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.jpeg.JpegDirectory;
import com.google.common.base.Charsets;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap;
import com.google.gson.JsonArray;
import com.google.gson.JsonPrimitive;
import com.mortennobel.imagescaling.ResampleFilters;
import com.mortennobel.imagescaling.ResampleOp;

public final class UploadTest {
	private static final Logger LOGGER = LoggerFactory.getLogger(UploadTest.class);

	private static HttpController.Http noCache(HttpController.Http http) {
		return http.header("Cache-Control", "no-cache, no-store, must-revalidate").header("Pragma", "no-cache").header("Expires", "0");
	}
	
	private static final File UPLOAD_DIRECTORY = new File("upload_temp");
	static {
		UPLOAD_DIRECTORY.mkdirs();
	}

	@Path("/get")
	public static final class GetController implements HttpController {
		@Route(method = HttpMethod.GET)
		public Http get(@QueryParameter("path") String path, @QueryParameter("size") Integer size) {
			try {
				if (size == null) {
					return Http.ok().stream(new FileInputStream(new File(UPLOAD_DIRECTORY, path)));
				} else {
					File f = new File(UPLOAD_DIRECTORY, path + "-" + size);
					if (!f.exists()) {
						synchronized (UPLOAD_DIRECTORY) {
							File g = new File(UPLOAD_DIRECTORY, ".temp");
							Images.save(Images.reduce(Images.load(new File(UPLOAD_DIRECTORY, path)), size), g);
							g.renameTo(f);
						}
					}
					return Http.ok().stream(new FileInputStream(f));
				}
			} catch (Exception e) {
				LOGGER.error("Error", e);
				return Http.internalServerError().content(e.getMessage());
			}
		}
	}
	
	private static final class RotatingBuffer {
		private final byte[] buffer;
		private int position = 0;
		private boolean full = false;
		public RotatingBuffer(int length) {
			buffer = new byte[length];
		}
		public int put(int b) {
			int old = -1;
			if (full) {
				old = buffer[position] & 0xFF;
			}
			buffer[position] = (byte)(b & 0xFF);
			position++;
			if (position == buffer.length) {
				full = true;
				position = 0;
			}
			return old;
		}
		public boolean equalsTo(byte[] array) {
			if (array.length != buffer.length) {
				return false;
			}
			if (!full) {
				return false;
			}
			int j = 0;
			int i = position;
			while (true) {
				if (buffer[i] != array[j]) {
					return false;
				}
				j++;
				i++;
				if (i == buffer.length) {
					i = 0;
				}
				if (i == position) {
					break;
				}
			}
			return true;
		}
		@Override
		public String toString() {
			if (!full) {
				return new String(buffer, 0, position);
			} else {
				return new String(buffer, position, buffer.length - position) + "/" + new String(buffer, 0, position);
			}
		}
	}
	
	private static String readNextLine(InputStream in) throws IOException {
		StringBuilder bb = new StringBuilder();
		int eol0 = '\r' & 0xFF;
		int eol1 = '\n' & 0xFF;
		boolean foundEol0 = false;
		while (true) {
			int b = in.read();
			if (b < 0) {
				throw new IOException("No EOL found");
			}
			if (foundEol0) {
				if (b == eol1) {
					return bb.toString();
				} else {
					bb.append((char) (eol0 & 0xFF));
					bb.append((char) (b & 0xFF));
				}
			} else {
				if (b == eol0) {
					foundEol0 = true;
				} else {
					bb.append((char) (b & 0xFF));
				}
			}
		}
	}
	
	private static final class Header {
		public final String title;
		public final ImmutableMap<String, String> values;
		public Header(String line) {
			List<String> l = Splitter.on(';').splitToList(line.trim());
			title = l.get(0).trim();
			Map<String, String> m = new HashMap<>();
			for (int v = 1; v < l.size(); v++) {
				List<String> kv = Splitter.on('=').splitToList(l.get(v).trim());
				m.put(kv.get(0), kv.get(1));
			}
			values = ImmutableMap.copyOf(m);
		}
		public boolean is(String t) {
			return title.equalsIgnoreCase(t);
		}
	}
	
	@Path("/upload")
	public static final class UploadController implements HttpController {
		@Route(method = HttpMethod.POST)
		public Http get(@HeaderParameter("Content-Type") String contentType, HttpPost post) {
			try {
				Header multipartHeader = new Header(contentType);
				if (!multipartHeader.is("multipart/form-data")) {
					throw new Exception("Not a multipart form data");
				}
				LOGGER.info("{}", multipartHeader.values);
				String boundary = "--" + multipartHeader.values.get("boundary");
				LOGGER.info("Boundary = {}", boundary);
				
				JsonArray result = new JsonArray();
				
				try (InputStream in = post.stream()) {
					String firstLine = readNextLine(in);
					if (!firstLine.equals(boundary)) {
						throw new Exception("Part not starting with boundary: " + firstLine);
					}
					while (true) {
						String fileName = null;
						while (true) {
							String header = readNextLine(in);
							LOGGER.info("Header = {}", header);
							if (header.isEmpty()) {
								break;
							}
							int k = header.indexOf(':');
							String headerKey = header.substring(0, k).trim().toLowerCase();
							Header headerValue = new Header(header.substring(k + 1));
							if (headerKey.equalsIgnoreCase("Content-Disposition")) {
								if (headerValue.is("form-data")) {
									fileName = headerValue.values.get("filename");
									if (fileName != null) {
										if (fileName.startsWith("\"")) {
											fileName = fileName.substring(1, fileName.length() - 1);
										}
									}
									LOGGER.info("filename = {}", fileName);
								}
							}
							if (headerKey.equalsIgnoreCase("Content-Type")) {
								String partContentType = headerValue.title;
								LOGGER.info("Content type = {}", partContentType);
							}
						}
						
						if (fileName == null) {
							fileName = "unknown";
						}
						
						File f = new File(UPLOAD_DIRECTORY, fileName);
						for (File ff : UPLOAD_DIRECTORY.listFiles()) {
							synchronized (UPLOAD_DIRECTORY) {
								if (ff.getName().startsWith(fileName)) {
									ff.delete();
								}
							}
						}
						
						String crlfBoundary = "\r\n" + boundary;
						byte[] boundaryAsBytes = crlfBoundary.getBytes(Charsets.US_ASCII);
						RotatingBuffer rotatingBuffer = new RotatingBuffer(crlfBoundary.length());
						try (OutputStream out = new BufferedOutputStream(new FileOutputStream(f))) {
							while (true) {
								int b = in.read();
								if (b < 0) {
									throw new Exception("Incomplete upload, boundary not found");
								}
								int old = rotatingBuffer.put(b);
								if (old >= 0) {
									out.write(old);
								}
								// LOGGER.info("ROTATING BUFFER {}", rotatingBuffer);
								// LOGGER.info("ROTATING BUFFER {}", "\r\n" + boundary);
								if (rotatingBuffer.equalsTo(boundaryAsBytes)) {
									result.add(new JsonPrimitive(fileName));
									String terminatingLine = readNextLine(in);
									if (terminatingLine.isEmpty()) {
										break;
									}
									if (!terminatingLine.equals("--")) {
										throw new Exception("Part not terminating with boundary--: " + terminatingLine);
									}
									
									return noCache(Http.ok().contentType(HttpContentType.json()).content(result.toString()));
								}
							}
						}
					}
				}
			} catch (Exception e) {
				LOGGER.error("Error", e);
				return Http.internalServerError().content(e.getMessage());
			}
		}
	}

	public static void main(String[] args) throws Exception {
		int port = Integer.parseInt(System.getProperty("port", "8081"));
		System.out.println("http://localhost:" + port);
		try (Ninio ninio = Ninio.create()) {
			Annotated.Builder a = Annotated.builder(HttpService.builder());
			a.register(null, new GetController());
			a.register(null, new UploadController());
			a.register(null, new ResourceAssets("/res", "index.html"));

			try (Listener tcp = ninio.create(TcpSocketServer.builder().bind(new Address(Address.ANY, port)))) {
				tcp.listen(ninio.create(HttpListening.builder().with(a.build())));
				new Wait().waitFor();
			}
		}
	}

	
	private static final class Images {
		private Images() {
		}
		
		@SuppressWarnings("unused")
		public static BufferedImage load(byte[] contents) throws IOException {
			if (contents.length == 0) {
				return null;
			}
			try (ByteArrayInputStream metadataIn = new ByteArrayInputStream(contents); ByteArrayInputStream fileIn = new ByteArrayInputStream(contents)) {
				return load(metadataIn, fileIn);
			}
		}

		public static BufferedImage load(File g) throws IOException {
			if (g.length() == 0L) {
				return null;
			}
			try (FileInputStream metadataIn = new FileInputStream(g); FileInputStream fileIn = new FileInputStream(g)) {
				return load(metadataIn, fileIn);
			}
		}
		
		private static BufferedImage load(InputStream metadataIn, InputStream fileIn) throws IOException {
			try {
				int orientation = 0;
				int width = 0;
				int height = 0;
				try {
					Metadata metadata = ImageMetadataReader.readMetadata(metadataIn);
		
					Directory directory = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
					if (directory != null) {
						JpegDirectory jpegDirectory = metadata.getFirstDirectoryOfType(JpegDirectory.class);
						if ((jpegDirectory != null) && directory.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
							orientation = directory.getInt(ExifIFD0Directory.TAG_ORIENTATION);
							width = jpegDirectory.getImageWidth();
							height = jpegDirectory.getImageHeight();
						}
					}
				} catch (Exception me) {
					LOGGER.warn("Could not get image orientation", me);
					orientation = 0;
				}
		
				AffineTransform transform = new AffineTransform();
		
				switch (orientation) {
				case 1:
					break;
				case 2: // Flip X
					transform.scale(-1d, 1d);
					transform.translate(-width, 0d);
					break;
				case 3: // PI rotation
					transform.translate(width, height);
					transform.rotate(Math.PI);
					break;
				case 4: // Flip Y
					transform.scale(1d, -1d);
					transform.translate(0d, -height);
					break;
				case 5: // - PI/2 and Flip X
					transform.rotate(-Math.PI / 2d);
					transform.scale(-1d, 1d);
					break;
				case 6: // -PI/2 and -width
					transform.translate(height, 0d);
					transform.rotate(Math.PI / 2d);
					break;
				case 7: // PI/2 and Flip
					transform.scale(-1d, 1d);
					transform.translate(-height, 0d);
					transform.translate(0d, width);
					transform.rotate(3d * Math.PI / 2d);
					break;
				case 8: // PI / 2
					transform.translate(0d, width);
					transform.rotate(3d * Math.PI / 2d);
					break;
				}
		
				BufferedImage destinationImage;
		
				BufferedImage sourceImage = ImageIO.read(fileIn);
				if (sourceImage == null) {
					throw new IOException("Invalid image");
				}
		
				if (orientation == 0) {
					destinationImage = sourceImage;
				} else {
					AffineTransformOp op = new AffineTransformOp(transform, AffineTransformOp.TYPE_BILINEAR);
					destinationImage = op.filter(sourceImage, null);
				}
		
				BufferedImage ii = new BufferedImage(destinationImage.getWidth(), destinationImage.getHeight(), BufferedImage.TYPE_INT_RGB);
				Graphics2D igg = ii.createGraphics();
				try {
					igg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);  
					igg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);  
					igg.drawImage(destinationImage, 0, 0, ii.getWidth(), ii.getHeight(), null);
				} finally {
					igg.dispose();
				}
				destinationImage = ii;
				
				return destinationImage;
			} catch (Exception ie) {
				throw new IOException("Image error", ie);
			}
		}
		
		public static void save(BufferedImage image, File to) throws IOException {
			if (image == null) {
				to.delete();
				return;
			}
			try (OutputStream os = new FileOutputStream(to)) {
				ImageIO.write(image, "jpg", os);
			}
		}
		
		@SuppressWarnings("unused")
		public static byte[] save(BufferedImage image) throws IOException {
			if (image == null) {
				return new byte[] {};
			}
			ByteArrayOutputStream os = new ByteArrayOutputStream();
			try {
				ImageIO.write(image, "jpg", os);
			} finally {
				os.close();
			}
			return os.toByteArray();
		}
		
		@SuppressWarnings("unused")
		public static BufferedImage rotate(BufferedImage sourceImage) throws IOException {
			if (sourceImage == null) {
				return null;
			}
			try {
				AffineTransform transform = new AffineTransform();

				transform.translate(sourceImage.getHeight(), 0d);
				transform.rotate(Math.PI / 2d);

				BufferedImage destinationImage;
		
				AffineTransformOp op = new AffineTransformOp(transform, AffineTransformOp.TYPE_BILINEAR);
				destinationImage = op.filter(sourceImage, null);
		
				BufferedImage ii = new BufferedImage(destinationImage.getWidth(), destinationImage.getHeight(), BufferedImage.TYPE_INT_RGB);
				Graphics2D igg = ii.createGraphics();
				try {
					igg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);  
					igg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);  
					igg.drawImage(destinationImage, 0, 0, ii.getWidth(), ii.getHeight(), null);
				} finally {
					igg.dispose();
				}
				destinationImage = ii;
				
				return destinationImage;
			} catch (Exception ie) {
				throw new IOException("Image error", ie);
			}
		}
		
		public static BufferedImage reduce(BufferedImage sourceImage, float width) throws IOException {
			if (sourceImage == null) {
				return null;
			}
			try {
				ResampleOp resizeOp = new ResampleOp((int) width, (int) (width * sourceImage.getHeight() / sourceImage.getWidth()));
				resizeOp.setFilter(ResampleFilters.getLanczos3Filter());
				return resizeOp.filter(sourceImage, null);
			} catch (Exception ie) {
				throw new IOException("Image error", ie);
			}
		}

	}
}
