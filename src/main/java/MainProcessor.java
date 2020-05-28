import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.SheetsScopes;
import com.google.api.services.sheets.v4.model.ValueRange;

import java.io.*;


import org.apache.commons.cli.*;
import org.apache.commons.lang.StringEscapeUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.Template;
import org.apache.velocity.app.Velocity;
import org.apache.velocity.exception.ResourceNotFoundException;
import org.apache.velocity.exception.ParseErrorException;
import org.apache.velocity.exception.MethodInvocationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.security.GeneralSecurityException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Date;
import java.util.Iterator;

import java.text.SimpleDateFormat;

/*
 Possible enhancement
  -c option for consolidate output
  -hd <template> for Header used for Bundle started
  -ft <template> for footor user for footer
  -pp <type> for pretty print (xml, json etc)
  -fhir <type> (fhir+xml, fhir+json, json, xml etc) - A helper that defaults header,footer, pp
 */

public class MainProcessor {
    private static final String APPLICATION_NAME = "HL7 MCC IG Generator";
    private static final JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();
    private static final String TOKENS_DIRECTORY_PATH = "tokens";

    private final Logger logger = LoggerFactory.getLogger(MainProcessor.class);

    /**
     * Global instance of the scopes required by this application
     * If modifying these scopes, delete your previously saved tokens/ folder.
     */
    private static final List<String> SCOPES = Collections.singletonList(SheetsScopes.SPREADSHEETS_READONLY);
    private static final String CREDENTIALS_FILE_PATH = "/credentials.json";

    private OutputProcessor output = new OutputProcessor();
    private String prefix = "";
    private String suffix = "";
    private String filterType = null;
    private String filterId = null;

    private String outputDirectory = "output";
    private String templateDirectory = "templates";
    private String spreadsheetId = "1E7ps-euW93GN4f5L61rOQAuK6RH8x69ZXAkD2i0VDS0";
    private String headerTemplateFullFileName = "templates/FHIR_XML_BUNDLE_START.vm";
    private String footerTemplateFullFileName = "templates/FHIR_XML_BUNDLE_END.vm";

    /**
     * Creates an authorized Credential object.
     *
     * @param HTTP_TRANSPORT The network HTTP Transport.
     * @return An authorized Credential object.
     * @throws IOException If the credentials.json file cannot be found.
     */
    private static Credential getCredentials(final NetHttpTransport HTTP_TRANSPORT) throws IOException {
        // Load client secrets.
        InputStream in = MainProcessor.class.getResourceAsStream(CREDENTIALS_FILE_PATH);
        if (in == null) {
            throw new FileNotFoundException("Resource not found: " + CREDENTIALS_FILE_PATH);
        }
        GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, new InputStreamReader(in));

        // Build flow and trigger user authorization request.
        GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                HTTP_TRANSPORT, JSON_FACTORY, clientSecrets, SCOPES)
                .setDataStoreFactory(new FileDataStoreFactory(new java.io.File(TOKENS_DIRECTORY_PATH)))
                .setAccessType("offline")
                .build();
        LocalServerReceiver receiver = new LocalServerReceiver.Builder().setPort(8888).build();
        return new AuthorizationCodeInstalledApp(flow, receiver).authorize("user");
    }


    /**
     * Processes the shared spreadssheet to generate resource files
     */
    public static void main(String... args) throws IOException, GeneralSecurityException {
        CommandLineParser parser = new DefaultParser();
        Options options = getOptions();
        try {
            CommandLine cmd = parser.parse(options, args);
            if (cmd.hasOption("help") || cmd.hasOption("h")) {
                HelpFormatter formatter = new HelpFormatter();
                formatter.printHelp("IGResourceGenerator", options);
               return;
            }
            // Ok we now had the command line
            MainProcessor processor = new MainProcessor();
            processor.process(cmd);


        } catch (ParseException e) {
            System.err.println("Error parsing command line: " + e.getLocalizedMessage());
            return;
        }

        return;
    }
    void process(CommandLine cmd) throws IOException, GeneralSecurityException {
        output.setLogger(logger);

        // Build a new authorized API client service.
        final NetHttpTransport HTTP_TRANSPORT = GoogleNetHttpTransport.newTrustedTransport();

        //Allow the spreadshetId to be changed
        if (cmd.hasOption("spid"))
        {
            spreadsheetId = cmd.getOptionValue("spid", spreadsheetId);
        }

        final String range = "Main!A1:ZZ";
        final String typeRange = "TypeMapping!A1:B";

        processOptions(cmd);


        Velocity.init();

        VelocityContext globalContext = new VelocityContext();
        addComputedVariablesToContext(globalContext);

        Sheets service = new Sheets.Builder(HTTP_TRANSPORT, JSON_FACTORY, getCredentials(HTTP_TRANSPORT))
                .setApplicationName(APPLICATION_NAME)
                .build();

        // Fetch the Data in the spreadsheet
        ValueRange data = service.spreadsheets().values()
                .get(spreadsheetId, range)
                .execute();

        // Fetch the Type Mapping
        ValueRange types = service.spreadsheets().values()
                .get(spreadsheetId, typeRange)
                .execute();

        HashMap<String, String> typeMapping = getTypeMapping(types);


        List<List<Object>> values = data.getValues();
        if (values == null || values.isEmpty()) {
            output.println("No data found.");
        } else {
            output.println("Processing Spreedsheet data");
            int rowCnt = 0;
            int size = 0;

            if (filterType != null)
            {
                output.println("Filtering by type: "+filterType);
                if (typeMapping.containsKey(filterType))
                {
                    filter(values,"Type", filterType);
                }
                else
                {
                    output.println("Type "+filterType+" currently not mapped to any templates");
                    return;
                }
            }

            if (filterId != null)
            {
                output.println("Filtering by Id: "+filterId);
                filter(values,"Id", filterId);
            }


            Iterator<List<Object>> itr = values.iterator();
            List<Object> row = itr.next();
            size = row.size();
            String[] variables  = new String[size];

            //Set up Variables Name to Row - Using the header row
            for (int a = 0; a < size; a++) {
                variables[a] = (String) row.get(a);
            }

            int processCnt = 0;

            if (!cmd.hasOption("b")) {
                //We are outputting separate files

                while (itr.hasNext()) {
                    row = itr.next();
                    //The First Row is the variable names
                    //Create a context for just this row, we should inherit the globalContext
                    VelocityContext context = new VelocityContext(globalContext);
                    //Fill in the variables based on what is in the row
                    for (int a = 0; a < size; a++) {
                        String value = null;
                        //Protect against short rows
                        if (a < row.size()) {
                            value = this.processCellData((String) row.get(a));

                        }

                        if (value != null) {
                            context.put(variables[a], value);
                        }
                    }
                    String id = (String) context.get("Id");
                    String type = (String) context.get("Type");
                    //We will skip unknown types
                    if (typeMapping.containsKey(type)) {
                        this.generateSingleOutput(context, type, typeMapping.get(type), id);
                        processCnt++;
                    } else {
                        output.println(String.format("Type %s not processed since no mapping is defined to a template for it", type));
                    }
                }
            }
            else
            {
                //We are outputting a bundle file
                StringBuffer resources = new StringBuffer();

                String outputFullFileName = String.format("%s" + File.separator + "%s%s%s.xml", outputDirectory, prefix, getBundleFileName(cmd), suffix);
                try {

                    while (itr.hasNext()) {
                        row = itr.next();
                        //The First Row is the variable names
                        //Create a context for just this row, we should inherit the globalContext
                        VelocityContext context = new VelocityContext(globalContext);
                        //Fill in the variables based on what is in the row
                        for (int a = 0; a < size; a++) {
                            String value = null;
                            //Protect against short rows
                            if (a < row.size()) {
                                value = this.processCellData((String) row.get(a));

                            }

                            if (value != null) {
                                context.put(variables[a], value);
                            }
                        }
                        String id = (String) context.get("Id");
                        String type = (String) context.get("Type");
                        //We will skip unknown types
                        if (typeMapping.containsKey(type)) {
                            try {
                                String resource = generateResource(context, type, typeMapping.get(type), outputFullFileName, id);
                                processCnt++;
                                resources.append(resource);
                            }
                            catch (ResourceNotFoundException rnfe) {
                                // couldn't find the template
                                output.printException(String.format("Unable to file template %s, error: %s", typeMapping.get(type), rnfe.getLocalizedMessage()));
                                throw rnfe;
                            } catch (ParseErrorException pee) {
                                // syntax error: problem parsing the template
                                output.printException(String.format("template %s, parse error: %s", typeMapping.get(type), pee.getLocalizedMessage()));
                                output.printException(pee);
                                throw pee;
                            } catch (MethodInvocationException mie) {
                                // something invoked in the template
                                // threw an exception
                                output.printException(mie);
                                throw mie;
                            } catch (Exception e) {
                                output.printException(e);
                                throw e;
                            }
                        } else {
                            output.println(String.format("Type %s not processed since no mapping is defined to a template for it", type));
                        }
                    }

                    StringWriter buffer = new StringWriter();
                    writeBundleHeader(buffer, processCnt);
                    buffer.write(resources.toString());
                    writeBundleEnd(buffer);
                    FileWriter file = new FileWriter(outputFullFileName);
                    file.write(toPrettyString(buffer.toString(), 2));
                    file.close();
                }
                catch (Exception e)
                {
                    output.printException(String.format("Exit with exception: %s", e.getLocalizedMessage()));
                }
            }
            output.println(String.format("Finished: %s processed",numberAsEntry(processCnt)));
        }

        //If we have hit any errors exit with a status code of 1
        if (output.hasLoggedExceptions())
        {
            System.exit(1);
        }
    }

    private String getBundleFileName(CommandLine cmd)
    {

        if (cmd.getOptionValue("b")!=null)
        {
            return cmd.getOptionValue("b");
        }

        return "bundle";
    }
    private void writeBundleHeader(StringWriter output, int size) throws IOException {
        output.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        output.write( "<Bundle xmlns=\"http://hl7.org/fhir\">\n");
	    output.write( "<type value=\"transaction\"/>\n");

        String total = String.format("<total value=\"%s\"/>\n", Integer.toString(size));
        output.write(total);

        /*
        Template template;
        context.put("GenEntries",Integer.toString(size));
        template = Velocity.getTemplate(headerTemplateFullFileName);
        template.merge(context, resource);

         */
    }

    private void writeBundleEnd(StringWriter output) throws IOException {
        output.write("</Bundle>");
    }

    private String numberAsEntry(int num)
    {
       return String.format("%s (%s) %s",NumberToEnglish.convert(num),Integer.toString(num),num==1?"entry":"entries");
    }

    private void filter(List<List<Object>> values,String columnId, String match)
    {
        int fieldIndex = 0;

        Iterator<List<Object>> itr = values.iterator();
        List<Object> row;

        //Deal with header column
        if (itr.hasNext()) {
            row = itr.next();
            fieldIndex = row.indexOf(columnId);
        }

        while (itr.hasNext()) {
            row = itr.next();
            String coltype = (String) row.get(fieldIndex);
            if (coltype.compareTo(match) != 0)
                itr.remove();
        }

    }
    /**
     * Reads the Mapping between type names and the template names into the HashMap
     */
    static HashMap<String, String> getTypeMapping(ValueRange types) {
        HashMap<String, String> typeMap = new HashMap<>();
        List<List<Object>> values = types.getValues();
        int rowCnt = 0;
        for (List<Object> row : values) {
            //We Ignore the header row
            if ((rowCnt > 0) && (row.size()>1) ) {
                String key = (String) row.get(0);
                if (StringUtils.isNotEmpty(key)){
                    typeMap.put(key, (String) row.get(1));
                }
            }
            rowCnt++;
        }
        return typeMap;
    }

    private String processCellData(String cell) {
        //Guard for null
        if (cell == null)
            return cell;
        //Remove Starting " if present
        if (cell.startsWith("\"")) {
            if (cell.length() == 1) {
                return null;
            }
            cell = cell.substring(1);
        }

        //Remove Trailing quote if present
        if (cell.endsWith("\"")) {
            if (cell.length() == 1) {
                return null;
            }
            cell = cell.substring(0, cell.length() - 2);
        }
        cell = StringEscapeUtils.escapeXml(cell);

        return cell.isEmpty() ? null : cell;
    }

    private String generateResource( VelocityContext context, String type, String templateName, String outputFullFileName, String id) throws ResourceNotFoundException,ParseErrorException,MethodInvocationException
    {
        String templateFulllFileName = String.format("%s" + File.separator + "%s", templateDirectory, templateName);
        StringWriter resource = new StringWriter();
        if (output.isVerbose()) {
            output.vprintln(String.format("Generating %s: %s to %s", type, id, outputFullFileName));
        }
        Template template;
            template = Velocity.getTemplate(templateFulllFileName);
            template.merge(context, resource);

        return resource.toString();
    }

    private void generateSingleOutput( VelocityContext context, String type, String templateName, String id) {

        String outputFullFileName = String.format("%s" + File.separator + "%s%s%s.xml", outputDirectory, prefix, id, suffix);


        try {
            String resource = generateResource(context, type, templateName, outputFullFileName, id);
            StringWriter buffer = new StringWriter();
            writeBundleHeader(buffer,1);
            buffer.write(resource);
            writeBundleEnd(buffer);
            FileWriter file = new FileWriter(outputFullFileName);
            file.write(toPrettyString(buffer.toString(), 2));
            file.close();

        } catch (ResourceNotFoundException rnfe) {
            // couldn't find the template
            output.printException(String.format("Unable to file template %s, error: %s", templateName, rnfe.getLocalizedMessage()));
        } catch (ParseErrorException pee) {
            // syntax error: problem parsing the template
            output.printException(String.format("template %s, parse error: %s", templateName, pee.getLocalizedMessage()));
            output.printException(pee);
        } catch (MethodInvocationException mie) {
            // something invoked in the template
            // threw an exception
            output.printException(mie);
        } catch (Exception e) {
            output.printException(e);
        }

    }

    public static void addComputedVariablesToContext(VelocityContext ctx) {
        //GenDate
        Date genDate = new Date();
        SimpleDateFormat genDateFormat = new SimpleDateFormat("YYYY-MM-dd");
        String genDateStr = genDateFormat.format(genDate);
        ctx.put("GenDate", genDateStr);

        //Add additional automated fields here.
    }

    // Command Line Options Handling

    private static Options getOptions() {
        Options options = new Options();

        Option help = new Option("h", "help", false, "print this help text");
        Option silent = new Option("q", "quit", false, "runs with no normal output");
        Option verbose = new Option("v", "verbose", false, "runs with verbose output");
        //Option TemplateDirectory (-td or -templateDirectory dir)
        //Option OutputDirectory (-od or -outputDirectory dir)
        //Option for Type (-t or -type name)
        //Options for Id (-i or -id name)
        Option prefix = Option.builder("p").argName("prefix").longOpt("prefix").hasArg().desc("a prefix for the generated file").build();
        Option suffix = Option.builder("s").argName("suffix").longOpt("suffix").hasArg().desc("a suffix for the generated file").build();
        Option id = Option.builder("i").argName("id").longOpt("id").hasArg().desc("the id of the items(s) to generate").build();
        Option type = Option.builder("t").argName("type").longOpt("type").hasArg().desc("the type of the items(s) to generate").build();
        Option td = Option.builder("td").argName("directory").longOpt("templates").hasArg().desc("the directory in which source templates are located").build();
        Option od = Option.builder("od").argName("directory").longOpt("output").hasArg().desc("the directory where generated output should be placed").build();
        Option bundle = Option.builder("b").argName("filename").optionalArg(true).longOpt("bundle").hasArg().desc("create the output in a single bundle file").build();
        Option spid = Option.builder("spid").argName("googlesheetid").longOpt("spreadsheetid").hasArg().desc("a goodle sheet id to use as the source").build();
        options.addOption(help);
        options.addOption(bundle);
        options.addOption(spid);
        options.addOption(silent);
        options.addOption(verbose);
        options.addOption(prefix);
        options.addOption(suffix);
        options.addOption(id);
        options.addOption(type);
        options.addOption(td);
        options.addOption(od);
        return options;
    }
    private void processOptions(CommandLine cmd)
    {
        if (cmd.hasOption("q")) output.setUnmuted(false);
        if (cmd.hasOption("v")) output.setVerbose(true);
        if (cmd.hasOption("p")) prefix = cmd.getOptionValue("p");
        if (cmd.hasOption("s")) suffix = cmd.getOptionValue("s");
        if (cmd.hasOption("td")) {
            String dir = cmd.getOptionValue("td");
            if (isDirectoryValid(dir))
            {
                templateDirectory = dir;
            }
            else
            {
                output.printException("Invalid template directory "+dir);
                return;
            }
        }
        if (cmd.hasOption("od")) {
            String dir = cmd.getOptionValue("od");
            if (isDirectoryValid(dir))
            {
                outputDirectory = dir;
            }
            else
            {
                output.printException("Output directory "+dir+" is either invalid or does not exist");
                return;
            }
        }
        if (cmd.hasOption("i")) filterId = cmd.getOptionValue("i");
        if (cmd.hasOption("t")) filterType = cmd.getOptionValue("t");
    }

    private boolean isDirectoryValid(String dir)
    {
        File file = new File(dir);
        return file.isDirectory();

    }

    public static String toPrettyString(String xml, int indent) {
        try {
            // Turn xml string into a document
            Document document = DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder()
                    .parse(new InputSource(new ByteArrayInputStream(xml.getBytes("utf-8"))));

            // Remove whitespaces outside tags
            document.normalize();
            XPath xPath = XPathFactory.newInstance().newXPath();
            NodeList nodeList = (NodeList) xPath.evaluate("//text()[normalize-space()='']",
                    document,
                    XPathConstants.NODESET);

            for (int i = 0; i < nodeList.getLength(); ++i) {
                Node node = nodeList.item(i);
                node.getParentNode().removeChild(node);
            }

            // Setup pretty print options
            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            transformerFactory.setAttribute("indent-number", indent);
            Transformer transformer = transformerFactory.newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");

            // Return pretty print xml string
            StringWriter stringWriter = new StringWriter();
            transformer.transform(new DOMSource(document), new StreamResult(stringWriter));
            return stringWriter.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
