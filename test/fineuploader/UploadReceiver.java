package fineuploader;

import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.net.URLDecoder;
import java.util.Arrays;
import java.util.regex.Pattern;

public class UploadReceiver extends HttpServlet
{
    private static File UPLOAD_DIR = new File("test/uploads");
    private static File TEMP_DIR = new File("test/uploadsTemp");

    private static String CONTENT_TYPE = "text/plain";
    private static String CONTENT_LENGTH = "Content-Length";
    private static int RESPONSE_CODE = 200;

    final Logger log = LoggerFactory.getLogger(UploadReceiver.class);


    @Override
    public void init() throws ServletException
    {
        UPLOAD_DIR.mkdirs();
    }

    @Override
    public void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException
    {
        RequestParser requestParser;

        try
        {
            resp.setContentType(CONTENT_TYPE);
            resp.setStatus(RESPONSE_CODE);

            if (ServletFileUpload.isMultipartContent(req))
            {
                MultipartUploadParser multipartUploadParser = new MultipartUploadParser(req, TEMP_DIR, getServletContext());
                requestParser = RequestParser.getInstance(req, multipartUploadParser);
                writeFileForMultipartRequest(requestParser, multipartUploadParser);
                writeResponse(resp.getWriter(), requestParser.generateError() ? "Generated error" : null);
            }
            else
            {
                requestParser = RequestParser.getInstance(req, null);
                writeFileForNonMultipartRequest(req, requestParser);
                writeResponse(resp.getWriter(), requestParser.generateError() ? "Generated error" : null);
            }
        } catch (Exception e)
        {
            log.error("Problem handling upload request", e);
            writeResponse(resp.getWriter(), e.getMessage());
        }
    }

    private void writeFileForNonMultipartRequest(HttpServletRequest req, RequestParser requestParser) throws Exception
    {
        String contentLengthHeader = req.getHeader(CONTENT_LENGTH);
        long expectedFileSize = Long.parseLong(contentLengthHeader);

        String partNumStr = req.getParameter("qqpartnum");
        if (partNumStr != null)
        {
            int partNum = Integer.parseInt(partNumStr);
            int totalParts = Integer.parseInt(req.getParameter("qqtotalparts"));
            String uuid = req.getParameter("qquuid");

            writeFile(req.getInputStream(), new File(UPLOAD_DIR, uuid + "_" + String.format("%05d", partNum)), null);

            if (totalParts-1 == partNum)
            {
                File[] parts = getPartitionFiles(UPLOAD_DIR, uuid);
                for (File part : parts)
                {
                    mergeFiles(requestParser.getFilename(), part);
                }
                deletePartitionFiles(UPLOAD_DIR, uuid);
            }
        }
        else
        {
            writeFile(req.getInputStream(), new File(UPLOAD_DIR, requestParser.getFilename()), expectedFileSize);
        }
    }


    private void writeFileForMultipartRequest(RequestParser requestParser, MultipartUploadParser multipartUploadParser) throws Exception
    {
        String partNumStr = multipartUploadParser.getParams().get("qqpartnum");
        if (partNumStr != null)
        {
            int partNum = Integer.parseInt(partNumStr);
            int totalParts = Integer.parseInt(multipartUploadParser.getParams().get("qqtotalparts"));
            String uuid = multipartUploadParser.getParams().get("qquuid");
            String originalFilename = URLDecoder.decode(multipartUploadParser.getParams().get("qqfilename"), "UTF-8");

            writeFile(requestParser.getUploadItem().getInputStream(), new File(UPLOAD_DIR, uuid + "_" + String.format("%05d", partNum)), null);

            if (totalParts-1 == partNum)
            {
                File[] parts = getPartitionFiles(UPLOAD_DIR, uuid);
                for (File part : parts)
                {
                    mergeFiles(originalFilename, part);
                }
                deletePartitionFiles(UPLOAD_DIR, uuid);
            }
        }
        else
        {
            writeFile(requestParser.getUploadItem().getInputStream(), new File(UPLOAD_DIR, requestParser.getFilename()), null);
        }
    }


    private static class PartitionFilesFilter implements FilenameFilter
    {
        private String filename;
        PartitionFilesFilter(String filename)
        {
            this.filename = filename;
        }

        @Override
        public boolean accept(File file, String s)
        {
            return s.matches(Pattern.quote(filename) + "_\\d+");
        }
    }

    private static File[] getPartitionFiles(File directory, String filename)
    {
        File[] files = directory.listFiles(new PartitionFilesFilter(filename));
        Arrays.sort(files);
        return files;
    }

    private static void deletePartitionFiles(File directory, String filename)
    {
        File[] partFiles = getPartitionFiles(directory, filename);
        for (File partFile : partFiles)
        {
            partFile.delete();
        }
    }

    private File mergeFiles(String filename, File partFile) throws Exception
   	{
   		File outputFile = new File(UPLOAD_DIR, filename);
   		FileOutputStream fos;
   		FileInputStream fis;
   		byte[] fileBytes;
   		int bytesRead = 0;
   		fos = new FileOutputStream(outputFile, true);
   		fis = new FileInputStream(partFile);
   		fileBytes = new byte[(int) partFile.length()];
   		bytesRead = fis.read(fileBytes, 0,(int)  partFile.length());
   		assert(bytesRead == fileBytes.length);
   		assert(bytesRead == (int) partFile.length());
   		fos.write(fileBytes);
   		fos.flush();
   		fis.close();
   		fos.close();

   		return outputFile;
   	}

    private File writeFile(InputStream in, File out, Long expectedFileSize) throws IOException
    {
        FileOutputStream fos = null;

        try
        {
            fos = new FileOutputStream(out);

            IOUtils.copy(in, fos);

            if (expectedFileSize != null)
            {
                Long bytesWrittenToDisk = out.length();
                if (!expectedFileSize.equals(bytesWrittenToDisk))
                {
                    log.warn("Expected file {} to be {} bytes; file on disk is {} bytes", new Object[] { out.getAbsolutePath(), expectedFileSize, 1 });
                    throw new IOException(String.format("Unexpected file size mismatch. Actual bytes %s. Expected bytes %s.", bytesWrittenToDisk, expectedFileSize));
                }
            }

            return out;
        }
        catch (Exception e)
        {
            throw new IOException(e);
        }
        finally
        {
            IOUtils.closeQuietly(fos);
        }
    }

    private void writeResponse(PrintWriter writer, String failureReason)
    {
        if (failureReason == null)
        {
            writer.print("{\"success\": true}");
        }
        else
        {
            writer.print("{\"error\": \"" + failureReason + "\"}");
        }
    }
}
