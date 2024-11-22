package vscode;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.cli.*;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;

import vscode.generated.Component;
import vscode.generated.Component.Configuration.Module;
import vscode.generated.Component.Configuration.Option;

/**
 * Hello world!
 *
 */
public class App {
    public static void main(String[] args) throws JAXBException, IOException {
        if (args.length != 1 && args.length != 3) {
            System.out.println("Usage: intellijtovscode ./path-to-runConfigurations --module ModuleName");
            System.out.println("\t--module ModuleName    this flag will override the runConfigurations default module name with the ModuleName provided.");
        } else {
            Options options = new Options();
            options.addOption("m", "module", true, "Override module name.");
            CommandLineParser parser = new DefaultParser();
            String pathToRunConfigurations;
            Boolean moduleNameOverride = false;
            String moduleNameOverrideString = null;
            try {
                CommandLine parsed = parser.parse(options, args);
                pathToRunConfigurations = parsed.getArgs()[0];
                if (parsed.hasOption("m")) {
                    moduleNameOverride = true;
                    moduleNameOverrideString = parsed.getOptionValue("m");
                }
            } catch (ParseException pe) {
                System.out.println(pe.getMessage());
                return;
            }

            File runConfigsFolder = new File(pathToRunConfigurations);
            LinkedHashMap<String, JsonElement> launch = new LinkedHashMap<>();
            launch.put("version", new JsonPrimitive("0.2.0"));

            ArrayList<JsonObject> configs = new ArrayList<>();
            
            // checks if the folder exists
            // if it doesn't exist, prints error and then exits
            if (!runConfigsFolder.exists()) {
                System.out.printf("\tRun Configs Folder '%s' Not Found%s", pathToRunConfigurations,
                        System.lineSeparator());
                return;
            }
            for (File runConfig : runConfigsFolder.listFiles()) {
                if (!FilenameUtils.getExtension(runConfig.getName()).equals("xml"))
                    continue;
                try (FileInputStream adrFile = new FileInputStream(runConfig.getPath())) {
                    LinkedHashMap<String, JsonElement> javaLaunchConfig = new LinkedHashMap<>();
                    javaLaunchConfig.put("type", new JsonPrimitive("java"));

                    // Creates the JAXB instance and unmarshalls the given runConfig file
                    JAXBContext ctx = JAXBContext.newInstance(Component.class);
                    Unmarshaller um = ctx.createUnmarshaller();
                    
                    // parses through the wrapper Component element
                    Component rootElement = (Component) um.unmarshal(adrFile);
                    // parses through the wrapper Configuration element
                    Component.Configuration config = rootElement.getConfiguration();

                    javaLaunchConfig.put("name", new JsonPrimitive(config.getName()));
                    javaLaunchConfig.put("request", new JsonPrimitive("launch"));

                    String className = null;
                    String moduleName = null;

                    // gets all the inner xml elements
                    for (Object obj : config.getOptionOrModuleOrMethod()) {
                        if (obj instanceof Option) {
                            Option option = (Option) obj;

                            String name = option.getName();
                            String value = option.getValue();
                            if (name.equals("WORKING_DIRECTORY")) {
                                String cwd = value.replace("$PROJECT_DIR$", "${workspaceFolder}");
                                if (cwd.lastIndexOf("/") == cwd.length() - 1) cwd = cwd.substring(0, cwd.length() - 1);
                                javaLaunchConfig.put("cwd", new JsonPrimitive(cwd));
                            } else if (name.equals("MAIN_CLASS_NAME")) {
                                className = value;
                            } else if (name.equals("PROGRAM_PARAMETERS")) {
                                JsonArray argsArray = new JsonArray();
                                for (String arg : value.split(" ")) {
                                    argsArray.add(arg);
                                }
                                javaLaunchConfig.put("args", argsArray);
                            }
                        } else if (obj instanceof Module) {
                            Module module = (Module) obj;

                            moduleName = module.getName();
                        }
                    }

                    // overwrites the moduleName if that setting is set
                    moduleName = moduleNameOverride ? moduleNameOverrideString : moduleName;
                    if (className != null && moduleName != null) {
                        javaLaunchConfig.put("mainClass", new JsonPrimitive(String.join("/", moduleName, className)));
                    } else if (className != null) {
                        javaLaunchConfig.put("mainClass", new JsonPrimitive(className));
                    }

                    // sorts the launch configs in the order that vscode launch configurations are typically sorted
                    javaLaunchConfig = javaLaunchConfig.entrySet().stream().sorted(Map.Entry.comparingByKey(new Comparator<String>() {
                        public int compare(String o1, String o2) {
                            List<String> orderedParams = Arrays.asList("type", "name", "request", "cwd", "mainClass", "args");
                            int diff = orderedParams.indexOf(o1) - orderedParams.indexOf(o2);
                            if (diff == 0) return diff;
                            return diff > 0 ? 1 : -1;
                        }
                    })).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (x, y) -> y, LinkedHashMap::new));

                    // creates a json object version that has all of it's values sorted based on javaLaunchConfig
                    JsonObject jsonLaunchConfig = new JsonObject();
                    for (Map.Entry<String, JsonElement> param : javaLaunchConfig.entrySet()) {
                        jsonLaunchConfig.add(param.getKey(), param.getValue());
                    }
                    configs.add(jsonLaunchConfig);
                }
            }

            // sorts the configs so that they are in alphabetical order
            Collections.sort(configs, new Comparator<JsonObject>() {
                public int compare(JsonObject ob1, JsonObject ob2) {
                    return ob1.get("name").getAsString().compareTo(ob2.get("name").getAsString());
                }
            });

            // converts the configs into a JsonArray
            JsonArray jsonConfigs = new JsonArray();
            for (JsonObject config : configs) {
                jsonConfigs.add(config);
            }
            launch.put("configurations", jsonConfigs);

            // pretty print mode on
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            // write launch.json file
            try (BufferedWriter writer = new BufferedWriter(new FileWriter("launch.json"))) {
                writer.write(gson.toJson(launch, launch.getClass()));
            }
            System.out.printf("launch.json created successfully in '%s'!%s", System.getProperty("user.dir"), System.lineSeparator());
        }
    }
}
