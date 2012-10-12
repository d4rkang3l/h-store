package edu.brown.mappings;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.apache.log4j.Logger;
import org.json.JSONException;
import org.json.JSONObject;
import org.voltdb.catalog.Database;
import org.voltdb.catalog.ProcParameter;
import org.voltdb.catalog.Procedure;
import org.voltdb.catalog.Statement;
import org.voltdb.catalog.StmtParameter;
import org.voltdb.utils.JarReader;

import edu.brown.catalog.CatalogUtil;
import edu.brown.logging.LoggerUtil.LoggerBoolean;
import edu.brown.utils.CollectionUtil;
import edu.brown.utils.ProjectType;

/**
 * @author pavlo
 */
public class ParametersUtil {
    private static final Logger LOG = Logger.getLogger(ParametersUtil.class);
    private static final LoggerBoolean debug = new LoggerBoolean(LOG.isDebugEnabled());

    /**
     *
     */
    public static class DefaultParameterMapping extends HashMap<String, Map<Integer, Integer>> {
        private static final long serialVersionUID = 1L;
        private final Map<String, Map<Integer, String>> stmt_param_names = new HashMap<String, Map<Integer, String>>();
        private final Map<String, Map<String, Integer>> stmt_param_idxs = new HashMap<String, Map<String, Integer>>();
        private final Map<Integer, String> proc_param_names = new HashMap<Integer, String>();
        private final Map<String, Integer> proc_param_idxs = new HashMap<String, Integer>();

        public DefaultParameterMapping() {
            super();
        }

        public Set<String> getStmtParamNames(String stmt_name) {
            return (this.stmt_param_idxs.get(stmt_name).keySet());
        }

        public String getStmtParamName(String stmt_name, int idx) {
            if (this.stmt_param_names.containsKey(stmt_name)) {
                return (this.stmt_param_names.get(stmt_name).get(idx));
            }
            return (null);
        }

        public Integer getStmtParamIndex(String stmt_name, String param_name) {
            if (this.stmt_param_idxs.containsKey(stmt_name)) {
                return (this.stmt_param_idxs.get(stmt_name).get(param_name));
            }
            return (null);
        }

        public Set<String> getProcParamNames() {
            return (this.proc_param_idxs.keySet());
        }

        public String getProcParamName(int idx) {
            return (this.proc_param_names.get(idx));
        }

        public Integer getProcParamIndex(String param_name) {
            return (this.proc_param_idxs.get(param_name));
        }

        /**
         * Adds a new mapping entry for a particular query in the catalogs. The
         * stmt_param is the index of the StmtParameter in the catalogs that
         * gets mapped to an index location of a ProcParamter
         * 
         * @param stmt_name
         *            - the name of the Statement for this parameter mapping
         * @param stmt_param
         *            - the index of the StmtParameter for this query
         * @param proc_param
         *            - the index of the ProcParameter for this StmtParameter
         *            corresponds to
         */
        public void add(String stmt_name, Integer stmt_param, String stmt_param_name, Integer proc_param, String proc_param_name) {
            if (!this.containsKey(stmt_name)) {
                this.put(stmt_name, new HashMap<Integer, Integer>());
                this.stmt_param_names.put(stmt_name, new HashMap<Integer, String>());
                this.stmt_param_idxs.put(stmt_name, new HashMap<String, Integer>());
            }
            this.get(stmt_name).put(stmt_param, proc_param);
            this.stmt_param_names.get(stmt_name).put(stmt_param, stmt_param_name);
            this.stmt_param_idxs.get(stmt_name).put(stmt_param_name, stmt_param);
            this.proc_param_names.put(proc_param, proc_param_name);
            this.proc_param_idxs.put(proc_param_name, proc_param);
        }
        
        /**
         * Adds a new mapping entry for a particular query in the catalogs.
         * @param proc_param
         * @param stmt_name
         * @param stmt_param
         */
        public void add(int proc_param, String stmt_name, int stmt_param) {
            if (!this.containsKey(stmt_name)) {
                this.put(stmt_name, new HashMap<Integer, Integer>());
                this.stmt_param_names.put(stmt_name, new HashMap<Integer, String>());
                this.stmt_param_idxs.put(stmt_name, new HashMap<String, Integer>());
            }
            this.get(stmt_name).put(stmt_param, proc_param);
        }
    } // END CLASS

    /**
     * Instead of doing all this work every time the class is loaded into the
     * namespace, we'll construct a static object that is initialized only when
     * the static methods are called. There you go old boy, I'm so clever!
     */
    private static ParametersUtil cache;

    private static ParametersUtil getCachedObject() {
        if (ParametersUtil.cache == null)
            ParametersUtil.cache = new ParametersUtil();
        return (ParametersUtil.cache);
    }

    /**
     * Load a ParameterMappingsSet from a project jar file.
     * @param catalog_db
     * @param jarPath
     * @return
     */
    public static ParameterMappingsSet getParameterMappingsSetFromJar(Database catalog_db, File jarPath) {
        LOG.debug("Loading ParameterMappingsSet from jar file at '" + jarPath.getAbsolutePath() + "'");
        if (!jarPath.exists()) {
            throw new RuntimeException("The catalog jar file '" + jarPath + "' does not exist");
        }
        
        String paramFile = catalog_db.getProject() + ".mappings";
        String serialized = null;
        try {
            serialized = JarReader.readFileFromJarfile(jarPath.getAbsolutePath(), paramFile);
        } catch (Exception ex) {
            String msg = String.format("Failed to find ParameterMappingSet file '%s' in '%s'",
                                       paramFile, jarPath);
            LOG.warn(msg, (debug.get() ? ex : null));
            return (null);
        }
        ParameterMappingsSet pms = null;
        try {
            pms = new ParameterMappingsSet();
            JSONObject json = new JSONObject(serialized);
            pms.fromJSON(json, catalog_db);
        } catch (JSONException ex) {
            String msg = String.format("Failed to load ParameterMappingSet '%s' from '%s'",
                                       paramFile, jarPath);
            throw new RuntimeException(msg, ex);
        }
        LOG.debug(String.format("Loaded ParameterMappingSet '%s' from '%s'", paramFile, jarPath));
        return (pms);
    }

    /**
     * Return the estimated Procedure parameter name
     * 
     * @param catalog_type
     * @param proc_name
     * @param idx
     * @return
     */
    public static String getProcParameterName(ProjectType project_type, String proc_name, int idx) {
        ParametersUtil putil = ParametersUtil.getCachedObject();
        Map<String, DefaultParameterMapping> map = putil.PARAMETER_MAPS.get(project_type);
        assert (map != null) : "Invalid catalog type '" + project_type + "'";
        if (map.containsKey(proc_name)) {
            DefaultParameterMapping param_map = map.get(proc_name);
            return (param_map.getProcParamName(idx));
        }
        return (null);
    }

    /**
     * Convert a ParameterCorrelations object into a map
     * String->ParameterMapping
     * 
     * @param catalog_db
     * @param mappings
     * @return
     * @throws Exception
     */
    public static Map<String, DefaultParameterMapping> generateXrefFromParameterMappings(Database catalog_db, ParameterMappingsSet mappings) {
        assert (catalog_db != null);
        assert (mappings != null);
        Map<String, DefaultParameterMapping> xrefMap = new HashMap<String, DefaultParameterMapping>();

        for (Procedure catalog_proc : catalog_db.getProcedures()) {
            String proc_name = catalog_proc.getName();
            DefaultParameterMapping map = new DefaultParameterMapping();

            for (Statement catalog_stmt : catalog_proc.getStatements()) {
                String stmt_name = catalog_stmt.getName();

                for (StmtParameter catalog_stmt_param : catalog_stmt.getParameters()) {
                    String stmt_param_name = catalog_stmt_param.getName();
                    Set<ParameterMapping> m = mappings.get(catalog_stmt, catalog_stmt_param);

                    if (m.isEmpty()) {
                        if (debug.get()) LOG.debug("No ParameterMapping found for " + CatalogUtil.getDisplayName(catalog_stmt_param) + ". Skipping...");
                        continue;
                    }
                    // HACK: I'm lazy, just take the first one for now
                    ParameterMapping c = CollectionUtil.first(m);
                    assert (c != null);

                    Integer proc_param = c.getProcParameter().getIndex();
                    String proc_param_name = c.getProcParameter().getName();
                    map.add(stmt_name, catalog_stmt_param.getIndex(), stmt_param_name, proc_param, proc_param_name);
                } // FOR (StmtParameter)
            } // FOR (Statement)
            xrefMap.put(proc_name, map);
        } // FOR (Procedure)
        assert (xrefMap.size() == catalog_db.getProcedures().size());
        return (xrefMap);
    }

    public static void applyParameterMappings(Database catalog_db, ParameterMappingsSet mappings) {
        Map<String, DefaultParameterMapping> proc_mapping = ParametersUtil.generateXrefFromParameterMappings(catalog_db, mappings);
        ParametersUtil.populateCatalog(catalog_db, proc_mapping, true);
        return;
    }

    /**
     * @param catalog_db
     * @param proc_mapping
     * @throws Exception
     */
    public static void populateCatalog(Database catalog_db, Map<String, DefaultParameterMapping> proc_mapping) throws Exception {
        populateCatalog(catalog_db, proc_mapping, false);
    }

    public static void populateCatalog(Database catalog_db, Map<String, DefaultParameterMapping> proc_mapping, boolean force) {
        // For each Procedure in the catalog, we need to find the matching record
        // in the mapping and update the catalog elements as necessary
        for (String proc_name : proc_mapping.keySet()) {
            Procedure catalog_proc = catalog_db.getProcedures().getIgnoreCase(proc_name);
            if (catalog_proc == null) {
                // throw new RuntimeException("Unknown Procedure name '" + proc_name + "' in ParameterMapping");
                continue;
            }
            if (debug.get()) LOG.debug("Updating parameter mapping for Procedure '" + proc_name + "'");
            
            DefaultParameterMapping map = proc_mapping.get(proc_name);
            for (String stmt_name : map.keySet()) {
                Statement catalog_stmt = catalog_proc.getStatements().get(stmt_name);
                if (catalog_stmt == null) {
                    throw new RuntimeException("Unknown Statement name '" + stmt_name + "' in ParameterMapping");
                }

                for (Integer stmt_param : map.get(stmt_name).keySet()) {
                    StmtParameter catalog_stmt_param = catalog_stmt.getParameters().get(stmt_param);

                    Integer proc_param = map.get(stmt_name).get(stmt_param);
                    ProcParameter catalog_proc_param = catalog_proc.getParameters().get(proc_param);

                    // Skip if it already has the proper ProcParameter set
                    if (!force && catalog_stmt_param.getProcparameter() != null && catalog_stmt_param.getProcparameter().equals(catalog_proc_param)) {
                        if (debug.get()) LOG.debug("Skipping parameter mapping in " + catalog_stmt + " because it is already set");
                    } else {
                        catalog_stmt_param.setProcparameter(catalog_proc_param);
                        if (debug.get()) LOG.debug("Added parameter mapping in Statement '" + stmt_name + "' from StmtParameter '" + catalog_stmt_param.getName() + "' to '" + catalog_proc_param.getName() + "'");
                    }
                } // FOR
            } // FOR
        } // FOR
        return;
    }


    //
    // Mappings between param names and procparam->stmtparam
    // Nasty I know, but what else can I do?
    //
    public final Map<ProjectType, Map<String, DefaultParameterMapping>> PARAMETER_MAPS = new HashMap<ProjectType, Map<String, DefaultParameterMapping>>();
}
