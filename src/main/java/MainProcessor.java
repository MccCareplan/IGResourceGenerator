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

import org.apache.commons.lang.StringEscapeUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.Template;
import org.apache.velocity.app.Velocity;
import org.apache.velocity.exception.ResourceNotFoundException;
import org.apache.velocity.exception.ParseErrorException;
import org.apache.velocity.exception.MethodInvocationException;
import org.eclipse.jetty.io.nio.SelectorManager;


import java.security.GeneralSecurityException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Date;

import java.text.SimpleDateFormat;

public class MainProcessor {
    private static final String APPLICATION_NAME = "Google Sheets API Java Quickstart";
    private static final JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();
    private static final String TOKENS_DIRECTORY_PATH = "tokens";

    /**
     * Global instance of the scopes required by this quickstart.
     * If modifying these scopes, delete your previously saved tokens/ folder.
     */
    private static final List<String> SCOPES = Collections.singletonList(SheetsScopes.SPREADSHEETS_READONLY);
    private static final String CREDENTIALS_FILE_PATH = "/credentials.json";

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
        // Build a new authorized API client service.
        final NetHttpTransport HTTP_TRANSPORT = GoogleNetHttpTransport.newTrustedTransport();
        final String spreadsheetId = "1E7ps-euW93GN4f5L61rOQAuK6RH8x69ZXAkD2i0VDS0";

        final String range = "Main!A1:ZZ";
        final String typeRange = "TypeMapping!A1:B";

        String outputDirectory = "output";
        String templateDirectory = "templates";

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
            System.out.println("No data found.");
        } else {
            int rowCnt = 0;
            int size = 0;

            // Setup global variables

            String[] variables = new String[1];

            for (List<Object> row : values) {
                //The First Row is the variable names
                if (rowCnt == 0) {
                    size = row.size();
                    variables = new String[size];
                    //Set up Variables Name to Row
                    for (int a = 0; a < size; a++) {
                        variables[a] = (String) row.get(a);
                    }
                }
                //All other rows need to be processed.
                else {
                    //Create a context for just this row, we should inherit the globalContext
                    VelocityContext context = new VelocityContext(globalContext);
                    //Fill in the variables based on what is in the row
                    for (int a = 0; a < size; a++) {
                        String value = null;
                        //Protect against short rows
                        if (a < row.size()) {
                            value = processCellData((String) row.get(a));

                        }

                        if (value != null) {
                            context.put(variables[a], value);
                        }
                    }
                    String id = (String) context.get("Id");
                    String type = (String) context.get("Type");
                    //We will skip unknown types
                    if (typeMapping.containsKey(type))
                    {
                        generateOutput(outputDirectory, templateDirectory, context, typeMapping.get(type), id);
                    } else {
                        System.out.println(String.format("Type %s not processed since no mapping is defined to a template for it", type));
                    }

                }
                rowCnt++;
            }
        }
    }

    static String processCellData(String cell) {
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

    /**
     * Reads the Mapping between type names and the template names into the HashMap
     */
    static HashMap<String, String> getTypeMapping(ValueRange types) {
        HashMap<String, String> typeMap = new HashMap<>();
        List<List<Object>> values = types.getValues();
        int rowCnt = 0;
        for (List<Object> row : values) {
            //We Ignore the header row
            if (rowCnt > 0) {
                String key = (String) row.get(0);
                if (StringUtils.isNotEmpty(key)) {
                    typeMap.put(key, (String) row.get(1));
                }
            }
            rowCnt++;
        }
        return typeMap;
    }

    static void generateOutput(String outputDirectory, String templateDirectory, VelocityContext context, String templateName, String id) {

        String templateFulllFileName = String.format("%s" + File.separator + "%s", templateDirectory, templateName);
        String outputFullFileName = String.format("%s" + File.separator + "%s.xml", outputDirectory, id);
        Template template;
        try {
            template = Velocity.getTemplate(templateFulllFileName);
            //StringWriter sw = new StringWriter();

            FileWriter file = new FileWriter(outputFullFileName);

            template.merge(context, file);
            file.close();

            //System.out.println(sw);


        } catch (ResourceNotFoundException rnfe) {
            // couldn't find the template
            System.out.println(String.format("Unable to file template %s", templateFulllFileName));
        } catch (ParseErrorException pee) {
            // syntax error: problem parsing the template
            System.out.println(String.format("template %s, parse error: %s", templateFulllFileName, pee.getLocalizedMessage()));
            pee.printStackTrace(System.out);
        } catch (MethodInvocationException mie) {
            // something invoked in the template
            // threw an exception
            mie.printStackTrace(System.out);
        } catch (Exception e) {
            e.printStackTrace(System.out);
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
}
