package horizon.aether.gaeserver.controller;

import horizon.aether.gaeserver.PMF;
import horizon.aether.gaeserver.model.CellLocationBlob;
import horizon.aether.gaeserver.model.CellLocationEntry;
import horizon.aether.gaeserver.model.DataConnectionStateBlob;
import horizon.aether.gaeserver.model.DataConnectionStateEntry;
import horizon.aether.gaeserver.model.Location;
import horizon.aether.gaeserver.model.ServiceStateBlob;
import horizon.aether.gaeserver.model.ServiceStateEntry;
import horizon.aether.gaeserver.model.SignalStrengthBlob;
import horizon.aether.gaeserver.model.SignalStrengthEntry;
import horizon.aether.gaeserver.model.SignalStrengthOnLocationChangeBlob;
import horizon.aether.gaeserver.model.SignalStrengthOnLocationChangeEntry;
import horizon.aether.gaeserver.model.TelephonyStateBlob;
import horizon.aether.gaeserver.model.TelephonyStateEntry;
import horizon.aether.gaeserver.model.Wifi;
import horizon.aether.gaeserver.model.WifiEntry;
import horizon.aether.utilities.CompressionUtils;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Map;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;

import javax.jdo.PersistenceManager;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;

import org.apache.commons.fileupload.FileItemIterator;
import org.apache.commons.fileupload.FileItemStream;
import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.codehaus.jackson.JsonParseException;
import org.codehaus.jackson.map.DeserializationConfig;
import org.codehaus.jackson.map.JsonMappingException;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.map.type.TypeFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 * Controller that handles the logic for the crowdsourcing of connectivity information.
 * The controller will mainly handle POST requests that contain compressed log files.
 */
@Controller
public class CrowdController {
    
    private static final Logger log = Logger.getLogger(CrowdController.class.getName());

    public enum UploadCompressionType {
    	 NONE, GZIP, DEFAULT;
    }
    
    /**
     * GET requests return empty page.
     */
    @RequestMapping(value = "/crowd/*", method = RequestMethod.GET)
    public String showCrowdGetScreen() {
        return "empty";
    }

    /**
     * POST requests are used to receive archived log files. The file is received,
     * uncompressed and parsed to retrieve the logging data that are eventually
     * persisted on the database.
     * @param req
     * @return an empty response
     */
    @RequestMapping(value = "/crowd/*", method = RequestMethod.POST)
    public String showCrowdPostScreen(HttpServletRequest req)
    {
        try
        {
        	// Look at all the submitted parts of the post data and process files as necessary
            ServletFileUpload upload = new ServletFileUpload();
            FileItemIterator iterator = upload.getItemIterator(req);
            while (iterator.hasNext())
            {
                FileItemStream item = iterator.next();
                InputStream stream = item.openStream();

                // Form fields will be returned too, but Files are only important
                if (!item.isFormField())
                {
                	// The uncompressed file can also be uploaded by naming the file upload field
                	// 'uncompressed' - otherwise assume it's compressed... 
                	UploadCompressionType compressionType = UploadCompressionType.DEFAULT;
                	if(item.getFieldName().contentEquals("uncompressedfile"))
                	{
                		compressionType = UploadCompressionType.NONE;
                	}
                	else if (item.getFieldName().contentEquals("gzippedfile"))
                	{
                		compressionType = UploadCompressionType.GZIP;
                	}
                	

                    OutputStream uncompressedStream = new ByteArrayOutputStream();
                    switch(compressionType)
                    {
	                    case NONE:
		                	int readByte;
		                	while((readByte = stream.read()) != -1)
		                	{
		                		uncompressedStream.write(readByte);
		                	}
		                break;

	                    case GZIP:
	                    	try
	                    	{
		                    	GZIPInputStream gzipInputStream =  new GZIPInputStream(stream);
		                    	int gzReadByte;
				                while((gzReadByte = gzipInputStream.read()) != -1)
			                	{
			                		uncompressedStream.write(gzReadByte);
			                	}
				                gzipInputStream.close();
	                    	}
	                    	catch(IOException ex)
	                    	{
		                        throw new ServletException("Failed to uncompress file (gz)", ex);
	                    	}
	                    break;
	                    
                    	default:
	                	// uncompress
	                    if (!CompressionUtils.uncompress(stream, uncompressedStream))
	                    {
	                        throw new ServletException("Failed to uncompress file (default)");
	                    }
	                    break;
                    }
                    // parse and persist file entries
                    parseAndPersistEntries(uncompressedStream.toString());
                }
            }
        }
        catch (FileUploadException ex) {
            log.warning(ex.toString());
        }
        catch (IOException ex) {
            log.warning(ex.toString());
        }
        catch (ServletException ex) {
            log.warning(ex.toString());
        }
        
        return "empty";
    }
    
    /**
     * Parses a string and creates the entry objects using Jackson's full data binding.
     * The entries are then persisted on the database. 
     * @param contents
     * @return
     */
    @SuppressWarnings("unchecked")
    public static void parseAndPersistEntries(String contents) {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationConfig.Feature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        
        PersistenceManager pm = PMF.get().getPersistenceManager();
        
        try {
            BufferedReader reader = new BufferedReader(new StringReader(contents));            
            String strLine;
            
            while ((strLine = reader.readLine()) != null) {
                Map<String,Object> entry = mapper.readValue(strLine, Map.class);
                
                // timestamp
                long timestamp = Long.parseLong(entry.get("timestamp").toString());
                
                // identifier
                String identifier = entry.get("identifier").toString();
                
                // location
                Location location = null;
                if (entry.get("location") != null) {
                    location = mapper.readValue(entry.get("location").toString(), Location.class);
                }
                
                // blob data
                String dataBlob = entry.get("dataBlob").toString();
                if (identifier.equals(WifiEntry.IDENTIFIER)) {
                    ArrayList<Wifi> networks = mapper.readValue(dataBlob, TypeFactory.collectionType(ArrayList.class, Wifi.class));                    
                    pm.makePersistent(new WifiEntry(timestamp, location, networks));
                }
                else if (identifier.equals(CellLocationEntry.IDENTIFIER)) {
                    CellLocationBlob blob = (CellLocationBlob) mapper.readValue(dataBlob, CellLocationBlob.class);
                    pm.makePersistent(new CellLocationEntry(timestamp, location, blob));
                }
                else if (identifier.equals(DataConnectionStateEntry.IDENTIFIER)) {
                    DataConnectionStateBlob blob = (DataConnectionStateBlob) mapper.readValue(dataBlob, DataConnectionStateBlob.class);
                    pm.makePersistent(new DataConnectionStateEntry(timestamp, location, blob));
                }
                else if (identifier.equals(ServiceStateEntry.IDENTIFIER)) {
                    ServiceStateBlob blob = (ServiceStateBlob) mapper.readValue(dataBlob, ServiceStateBlob.class);
                    pm.makePersistent(new ServiceStateEntry(timestamp, location, blob));
                }
                else if (identifier.equals(SignalStrengthEntry.IDENTIFIER)) {
                    SignalStrengthBlob blob = (SignalStrengthBlob) mapper.readValue(dataBlob, SignalStrengthBlob.class);
                    pm.makePersistent(new SignalStrengthEntry(timestamp, location, blob));
                }
                else if (identifier.equals(SignalStrengthOnLocationChangeEntry.IDENTIFIER)) {
                    SignalStrengthOnLocationChangeBlob blob = (SignalStrengthOnLocationChangeBlob) mapper.readValue(dataBlob, SignalStrengthOnLocationChangeBlob.class);
                    pm.makePersistent(new SignalStrengthOnLocationChangeEntry(timestamp, location, blob));
                    SignalStrengthBlob blob2 = (SignalStrengthBlob) mapper.readValue(dataBlob, SignalStrengthBlob.class);
                    pm.makePersistent(new SignalStrengthEntry(timestamp, location, blob2));
                }
                else if (identifier.equals(TelephonyStateEntry.IDENTIFIER)) {
                    TelephonyStateBlob blob = (TelephonyStateBlob) mapper.readValue(dataBlob, TelephonyStateBlob.class);
                    pm.makePersistent(new TelephonyStateEntry(timestamp, location, blob));
                }
            }
        }
        catch (JsonParseException e) { 
            log.warning(e.toString()); 
        }
        catch (JsonMappingException e) {
            log.warning(e.toString());
        }
        catch (IOException e) {
            log.warning(e.toString());
        }

        pm.close();
    }
}
