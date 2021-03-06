/**
 * Copyright CSIRO Australian e-Health Research Centre (http://aehrc.com).
 * All rights reserved. Use is subject to license terms and conditions.
 */
package au.csiro.ontology.importer.rf2;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.Set;
import java.util.TreeSet;

import org.apache.log4j.Logger;

import au.csiro.ontology.Ontology;
import au.csiro.ontology.importer.BaseImporter;
import au.csiro.ontology.importer.ImportException;
import au.csiro.ontology.input.Input;
import au.csiro.ontology.input.Input.InputType;
import au.csiro.ontology.input.Inputs;
import au.csiro.ontology.input.ModuleInfo;
import au.csiro.ontology.input.RF2Input;
import au.csiro.ontology.input.Version;
import au.csiro.ontology.model.Axiom;
import au.csiro.ontology.model.Concept;
import au.csiro.ontology.model.ConceptInclusion;
import au.csiro.ontology.model.Conjunction;
import au.csiro.ontology.model.Datatype;
import au.csiro.ontology.model.DecimalLiteral;
import au.csiro.ontology.model.Existential;
import au.csiro.ontology.model.IntegerLiteral;
import au.csiro.ontology.model.Literal;
import au.csiro.ontology.model.NamedConcept;
import au.csiro.ontology.model.NamedFeature;
import au.csiro.ontology.model.NamedRole;
import au.csiro.ontology.model.Operator;
import au.csiro.ontology.model.Role;
import au.csiro.ontology.model.RoleInclusion;
import au.csiro.ontology.snomed.refset.rf2.IModuleDependencyRefset;
import au.csiro.ontology.snomed.refset.rf2.ModuleDependency;
import au.csiro.ontology.snomed.refset.rf2.RefsetRow;
import au.csiro.ontology.util.IProgressMonitor;

/**
 * Imports ontologies specified in RF2 format into the internal representation.
 *
 * @author Alejandro Metke
 *
 */
public class RF2Importer extends BaseImporter {

    /**
     * Logger.
     */
    private final static Logger log = Logger.getLogger(RF2Importer.class);

    /**
     * List of problems found while importing.
     */
    protected final List<String> problems = new ArrayList<String>();

    /**
     * Queue with the inputs to process.
     */
    protected final Queue<RF2Input> inputs = new LinkedList<RF2Input>();

    /**
     * Imports an ontology using the supplied configuration object.
     *
     * @param input
     */
    public RF2Importer(RF2Input input) {
        this.inputs.add(input);
    }

    /**
     * Imports a set of ontologies using the supplied configuration object.
     *
     * @param inputs
     */
    public RF2Importer(Collection<Input> inputs) {
        for(Input in : inputs) {
            if(in instanceof RF2Input) {
                this.inputs.add((RF2Input) in);
            }
        }
    }

    @Override
    public Iterator<Ontology> getOntologyVersions(IProgressMonitor monitor) throws ImportException {
        return new OntologyInterator(monitor);
    }

    @Override
    public List<String> getProblems() {
        return problems;
    }

    /**
     * Loads all the module dependency information from all RF2 inputs into a single {@link IModuleDependencyRefset}.
     *
     * @return
     * @throws ImportException
     */
    protected IModuleDependencyRefset loadModuleDependencies(RF2Input input) throws ImportException {
        Set<InputStream> iss = new HashSet<InputStream>();
        InputType inputType = input.getInputType();
        for(String md : input.getModuleDependenciesRefsetFiles()) {
            final String message = "Unable to load module " +
                    "dependencias. Please check your input configuration " +
                    "file. (input type = "+inputType+", file="+md+")";
            try {
                iss.add(input.getInputStream(md));
            } catch (NullPointerException e) {
                log.error(message, e);
                throw new ImportException(message, e);
            } catch (IOException e) {
                log.error(message, e);
                throw new ImportException(message, e);
            }
        }

        IModuleDependencyRefset res = RefsetImporter.importModuleDependencyRefset(iss);
        return res;
    }

    /**
     * Determines which modules and versions should be loaded based on the {@link Inputs} object. Returns a {@link Map}
     * with the module ids as keys and the set of versions to import as values.
     *
     * @return
     */
    protected Map<String, Set<Version>> getModuleVersionsToLoad(RF2Input in) {
        Map<String, Set<Version>> res = new HashMap<String, Set<Version>>();
        for(ModuleInfo mi : in.getModules()) {
            String moduleId = mi.getId();
            Set<Version> versionsToLoad = res.get(moduleId);
            if(versionsToLoad == null) {
                versionsToLoad = new HashSet<Version>();
                res.put(moduleId, versionsToLoad);
            }
            for(Version v : mi.getVersions()) {
                versionsToLoad.add(v);
            }
        }

        return res;
    }

    /**
     * Populates concrete domains information.
     *
     * @param cdMap
     * @param referencedComponentId
     * @param featureId
     * @param operator
     * @param value
     * @param unit
     */
    protected void populateCDs(Map<String, List<String[]>> cdMap, String referencedComponentId, String featureId,
            String operator, String value, String unit) {
        List<String[]> list;
        // my ( $comp, $feature, $op, $value, $unit ) = @_;
        if (!cdMap.containsKey(referencedComponentId)) {
            list = new ArrayList<String[]>();
            cdMap.put(referencedComponentId, list);
        } else {
            list = cdMap.get(referencedComponentId);
        }
        list.add(new String[] { featureId, operator, value, unit });
    }

    protected <R extends RefsetRow> void loadReferenceSet(RF2Input input, String refsetFile, Map<String, String> modMap,
            Map<String, R> refsetMap, IRefsetFactory<R> factory)
            throws ImportException {

        Set<String> unknownModules = new HashSet<String>();
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(input.getInputStream(refsetFile), Charset.forName("UTF8")));
            String line = br.readLine(); // Skip first line

            while (null != (line = br.readLine())) {
                if (line.trim().length() < 1) {
                    continue;
                }
                String[] fields = line.split("\t");

                if (fields.length < 6) {
                    throw new RuntimeException("Refset: Mis-formatted line, expected >= 6 tab-separated fields, " +
                    		"got: " + line);
                }

                final String id = fields[0];
                final String effectiveTime = fields[1];
                final String active = fields[2];
                final String moduleId = fields[3];
                final String refsetId = fields[4];
                final String referencedComponentId = fields[5];

                String tgtVer = modMap.get(moduleId);
                if (null == tgtVer) {
                    unknownModules.add(moduleId);
                    continue;
                }

                // FIXME Use a more sophisticated (date/time aware) comparison method
                int rel = effectiveTime.compareTo(tgtVer);
                if (rel <= 0) {
                    RefsetRow currRow = refsetMap.get(id);
                    if (currRow == null || effectiveTime.compareTo(currRow.getEffectiveTime()) > 0) {
                        String[] extras = new String[fields.length - 6];
                        System.arraycopy(fields, 6, extras, 0, extras.length);
                        R rr = factory.create(id, effectiveTime, active, moduleId, refsetId, referencedComponentId,
                                extras);
                        refsetMap.put(id, rr);
                    }
                }
            }
        } catch (Throwable t) {
            log.error(t);
            throw new ImportException("Unable to load reference set file. Please check your input configuration file " +
                    "(input type = " + input.getInputType() + ", file=" + refsetFile + ")");
        } finally {
            for (String moduleId : unknownModules) {
                log.warn("Refset: No version information known for " + moduleId + " found in " + refsetFile);
            }
        }
    }

    /**
     * Returns a {@link VersionRows} object for an {@link ImportEntry}.
     *
     * @param entry
     * @return
     * @throws ImportException
     */
    protected VersionRows getBundle(ImportEntry entry) throws ImportException {

        // Add module information to map for easy lookup
        Map<String, String> modMap = new HashMap<String, String>();
        for (Module module : entry.getModules()) {
            String modId = module.getModuleId();
            String modVer = module.getModuleVersion();
            modMap.put(modId, modVer);
        }
        for (Entry<String, String> mapEntry: modMap.entrySet()) {
            log.info("Modules: " + mapEntry.getKey() + "\t" + mapEntry.getValue());
        }

        // Map needed to find the correct version of each concept to load for this import entry
        Map<String, ConceptRow> conceptMap = new HashMap<String, ConceptRow>();

        // Map needed to find the correct version of each relationship to load
        // for this import entry
        Map<String, RelationshipRow> relationshipMap = new HashMap<String, RelationshipRow>();

        Map<String, RefsetRow> cdMap = new HashMap<String, RefsetRow>();

        RF2Input input = (RF2Input) entry.getInput();

        InputType inputType = input.getInputType();
        Set<String> conceptsFiles = input.getConceptsFiles();
        log.info("Read concepts info");
        for(String conceptsFile : conceptsFiles) {
            final String message = "Unable to load concepts file. " +
                    "Please check your input configuration file. " +
                    "(input type = "+inputType+", file="+conceptsFile+")";
            try {
                loadConceptRows(modMap, conceptMap, input.getInputStream(conceptsFile));
            } catch (NullPointerException e) {
                log.error(message, e);
                throw new ImportException(message, e);
            } catch (IOException e) {
                log.error(message, e);
                throw new ImportException(message, e);
            }
        }

        // Load relationships
        Set<String> relationshipsFiles = input.getStatedRelationshipsFiles();
        if(relationshipsFiles == null || relationshipsFiles.isEmpty()) {
            log.info("Read inferred relationships info");
            relationshipsFiles = input.getRelationshipsFiles();
        } else {
            log.info("Read stated relationships info");
        }

        if(relationshipsFiles == null || relationshipsFiles.isEmpty()) {
            throw new ImportException("No relationships files was specified.");
        }

        for(String relationshipsFile : relationshipsFiles) {
            final String message = "Unable to load relationships file. Please check your input configuration " +
                    "file. (input type = " + inputType+", file=" + relationshipsFile+")";
            try {
                loadRelationshipRows(modMap, relationshipMap, input.getInputStream(relationshipsFile));
            } catch (NullPointerException e) {
                log.error(message, e);
                throw new ImportException(message, e);
            } catch (IOException e) {
                log.error(message, e);
                throw new ImportException(message, e);
            }

            // Load concrete domains refsets
            log.info("Read concrete domains reference set info");
            for (String filename : input.getConcreteDomainRefsetFiles()) {
                try {
                    loadReferenceSet(input, filename, modMap, cdMap, IRefsetFactory.CD);
                } catch (ArrayIndexOutOfBoundsException e) {
                    final String msg = "Error loading concrete domains reference set: " + filename +
                            ". Possibly has wrong number of columns.";
                    log.error(msg, e);
                    throw new ImportException(msg, e);
                }
            }
        }

        VersionRows vr = new VersionRows(conceptMap.values(), relationshipMap.values(), cdMap.values());

        conceptMap = null;
        relationshipMap = null;
        cdMap = null;

        return vr;
    }

    protected void loadRelationshipRows(Map<String, String> modMap, Map<String, RelationshipRow> relationshipMap,
            final InputStream inputStream) throws IOException {
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(inputStream));
            String line = br.readLine(); // Skip first line
            while (null != (line = br.readLine())) {
                if (line.trim().length() < 1) {
                    continue;
                }
                int idx1 = line.indexOf('\t');
                int idx2 = line.indexOf('\t', idx1 + 1);
                int idx3 = line.indexOf('\t', idx2 + 1);
                int idx4 = line.indexOf('\t', idx3 + 1);
                int idx5 = line.indexOf('\t', idx4 + 1);
                int idx6 = line.indexOf('\t', idx5 + 1);
                int idx7 = line.indexOf('\t', idx6 + 1);
                int idx8 = line.indexOf('\t', idx7 + 1);
                int idx9 = line.indexOf('\t', idx8 + 1);

                // 0..idx1 == id
                // idx1+1..idx2 == effectiveTime
                // idx2+1..idx3 == active
                // idx3+1..idx4 == moduleId
                // idx4+1..idx5 == sourceId
                // idx5+1..idx6 == destinationId
                // idx6+1..idx7 == relationshipGroup
                // idx7+1..idx8 == typeId
                // idx8+1..idx9 == characteristicTypeId
                // idx9+1..end == modifierId

                if (idx1 < 0 || idx2 < 0 || idx3 < 0 || idx4 < 0 ||
                        idx5 < 0 || idx6 < 0 || idx7 < 0 || idx8 < 0 ||
                        idx9 < 0) {
                    br.close();
                    throw new RuntimeException("Relationships: Mis-formatted line, expected 10 " +
                        "tab-separated fields, got: " + line);
                }

                final String id = line.substring(0, idx1);
                final String effectiveTime = line.substring(idx1 + 1, idx2);
                final String active = line.substring(idx2 + 1, idx3);
                final String moduleId = line.substring(idx3 + 1, idx4);
                final String sourceId = line.substring(idx4 + 1, idx5);
                final String destinationId = line.substring(idx5 + 1, idx6);
                final String relationshipGroup = line.substring(idx6 + 1, idx7);
                final String typeId = line.substring(idx7 + 1, idx8);
                final String characteristicTypeId = line.substring(idx8 + 1, idx9);
                final String modifierId = line.substring(idx9 + 1);

                String tgtVer = modMap.get(moduleId);
                if(tgtVer == null) continue;
                int rel = effectiveTime.compareTo(tgtVer);
                if(rel <= 0) {
                    RelationshipRow currRelationshipRow = relationshipMap.get(id);
                    if(currRelationshipRow == null || effectiveTime.compareTo(
                            currRelationshipRow.getEffectiveTime()) > 0) {
                        RelationshipRow rr = new RelationshipRow(id, effectiveTime, active, moduleId, sourceId,
                                destinationId, relationshipGroup, typeId, characteristicTypeId, modifierId);
                        relationshipMap.put(id, rr);
                    }
                }
            }
        } finally {
            if(br != null) {
                try { br.close(); } catch(Exception e) {};
            }
        }
    }

    protected void loadConceptRows(Map<String, String> modMap, Map<String, ConceptRow> conceptMap,
            final InputStream inputStream) throws IOException, UnsupportedEncodingException {
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(inputStream));
            String line = br.readLine(); // Skip first line

            while (null != (line = br.readLine())) {
                line = new String(line.getBytes(), "UTF8");
                if (line.trim().length() < 1) {
                    continue;
                }
                int idx1 = line.indexOf('\t');
                int idx2 = line.indexOf('\t', idx1 + 1);
                int idx3 = line.indexOf('\t', idx2 + 1);
                int idx4 = line.indexOf('\t', idx3 + 1);

                // 0..idx1 == id
                // idx1+1..idx2 == effectiveTime
                // idx2+1..idx3 == active
                // idx3+1..idx4 == moduleId
                // idx4+1..end == definitionStatusId

                if (idx1 < 0 || idx2 < 0 || idx3 < 0 || idx4 < 0) {
                    br.close();
                    throw new RuntimeException("Concepts: Mis-formatted line, expected at least 5 tab-separated " +
                        "fields, got: " + line);
                }

                final String id = line.substring(0, idx1);
                final String effectiveTime = line.substring(idx1 + 1, idx2);
                final String active = line.substring(idx2 + 1, idx3);
                final String moduleId = line.substring(idx3 + 1, idx4);
                final String definitionStatusId = line.substring(idx4 + 1);

                String tgtVer = modMap.get(moduleId);
                if(tgtVer == null) continue;
                int rel = effectiveTime.compareTo(tgtVer);
                if(rel <= 0) {
                    ConceptRow currConceptRow = conceptMap.get(id);
                    if(currConceptRow == null || effectiveTime.compareTo(currConceptRow.getEffectiveTime()) > 0) {
                        ConceptRow cr = new ConceptRow(id, effectiveTime, active, moduleId, definitionStatusId);
                        conceptMap.put(id, cr);
                    }
                }
            }
        } finally {
            if(br != null) {
                try { br.close(); } catch(Exception e) {};
            }
        }
    }

    protected Concept getConcept(String id, Map<String, Concept> ci) {
        Concept c = ci.get(id);
        if (c == null) {
            c = new NamedConcept(id);
            ci.put(id, c);
        }
        return c;
    }

    protected NamedRole getRole(String id, Map<String, NamedRole> ri) {
        NamedRole r = ri.get(id);
        if (r == null) {
            r = new NamedRole(id);
            ri.put(id, r);
        }
        return r;
    }

    protected NamedFeature getFeature(String id, Map<String, NamedFeature> fi) {
        NamedFeature f = fi.get(id);
        if (f == null) {
            f = new NamedFeature(id);
            fi.put(id, f);
        }
        return f;
    }

    protected void populateParent(String src, String tgt, Map<String, Set<String>> parents) {
        Set<String> prs = parents.get(src);
        if (prs == null) {
            prs = new TreeSet<String>();
            parents.put(src, prs);
        }
        prs.add(tgt);
    }

    protected void populateChildren(String src, String tgt, Map<String, Set<String>> children) {
        Set<String> prs = children.get(src);
        if (prs == null) {
            prs = new TreeSet<String>();
            children.put(src, prs);
        }
        prs.add(tgt);
    }

    protected void populateRels(String comp, String src, String role, String tgt, String group,
            Map<String, List<String[]>> rels) {
        List<String[]> val = rels.get(src);
        if (val == null) {
            val = new ArrayList<String[]>();
            rels.put(src, val);
        }
        val.add(new String[] { comp, role, tgt, group });
    }

    protected void populateRoles(Set<String> roles, String parentSCTID, String rightIdentityIds, Map<String,
            Set<String>> children, Map<String, Map<String, String>> rolesMap) {
        if(roles == null) return;
        for (String role : roles) {
            Set<String> cs = children.get(role);
            if (cs != null) {
                populateRoles(cs, role, rightIdentityIds, children, rolesMap);
            }
            String[] ris = rightIdentityIds.split("[,]");
            String ri = (ris[0].equals(role)) ? ris[1] : null;
            if (ri != null) {
                populateRoleDef(role, ri, parentSCTID, rolesMap);
            } else {
                populateRoleDef(role, "", parentSCTID, rolesMap);
            }
        }
    }

    protected void populateRoleDef(String code, String rightId, String parentRole, Map<String,
            Map<String, String>> roles) {
        Map<String, String> vals = roles.get(code);
        if (vals == null) {
            vals = new HashMap<String, String>();
            roles.put(code, vals);
        }
        vals.put("rightID", rightId);
        vals.put("parentrole", parentRole);
    }

    protected Set<Set<RoleValuePair>> groupRoles(List<String[]> groups) {
        Map<String, Set<RoleValuePair>> roleGroups =
                new HashMap<String, Set<RoleValuePair>>();

        for (String[] group : groups) {
            String comp = group[0];
            String attr = group[1];
            String val = group[2];
            String roleGroup = group[3];
            Set<RoleValuePair> lrvp = roleGroups.get(roleGroup);
            if (lrvp == null) {
                lrvp = new HashSet<RoleValuePair>();
                roleGroups.put(roleGroup, lrvp);
            }
            lrvp.add(new RoleValuePair(attr, val, comp));
        }

        Set<Set<RoleValuePair>> res = new HashSet<Set<RoleValuePair>>();
        for (String roleGroup : roleGroups.keySet()) {
            Set<RoleValuePair> val = roleGroups.get(roleGroup);

            // 0 indicates not grouped
            if ("0".equals(roleGroup)) {
                for (RoleValuePair rvp : val) {
                    Set<RoleValuePair> sin = new HashSet<RoleValuePair>();
                    sin.add(rvp);
                    res.add(sin);
                }
            } else {
                Set<RoleValuePair> item = new HashSet<RoleValuePair>();
                for (RoleValuePair trvp : val) {
                    item.add(trvp);
                }
                res.add(item);
            }
        }
        return res;
    }

    /**
     * Hook method for subclasses to override.
     *
     * @param vr
     * @param rootModuleId
     * @param rootModuleVersion
     * @param includeInactiveAxioms
     * @return
     */
    protected OntologyBuilder getOntologyBuilder(VersionRows vr, String rootModuleId, String rootModuleVersion,
            Map<String, String> metadata) {
        return new OntologyBuilder(vr, rootModuleId, rootModuleVersion, metadata);
    }

    /**
     * Represents a role-value pair.
     *
     * @author Alejandro Metke
     *
     */
    protected class RoleValuePair {
        final String role;
        final String value;
        final String id;

        RoleValuePair(String role, String value, String id) {
            this.role = role;
            this.value = value;
            this.id = id;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + getOuterType().hashCode();
            result = prime * result + ((role == null) ? 0 : role.hashCode());
            result = prime * result + ((value == null) ? 0 : value.hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            RoleValuePair other = (RoleValuePair) obj;
            if (!getOuterType().equals(other.getOuterType()))
                return false;
            if (role == null && other.role != null)
                return false;
            else if (!role.equals(other.role))
                return false;
            if (value == null && other.value != null)
                return false;
            else if (!value.equals(other.value))
                return false;
            return true;
        }

        private RF2Importer getOuterType() {
            return RF2Importer.this;
        }
    }

    class OntologyInterator implements Iterator<Ontology> {

        private final Queue<ImportEntry> entries = new LinkedList<ImportEntry>();
        @SuppressWarnings("unused")
        private final IProgressMonitor monitor;

        private void processNext() throws ImportException {
            RF2Input in = inputs.remove();

            // 1. Load module dependencies
            log.info("Loading module dependencies");
            IModuleDependencyRefset mdr = loadModuleDependencies(in);

            if(mdr == null) {
                throw new ImportException("Couldn't load module dependency reference set for RF2 input files.");
            }

            // Each map entry contains a map of modules indexed by version
            Map<String, Map<String, ModuleDependency>> deps =
                    mdr.getModuleDependencies();

            // 2. Determine which modules and versions must be loaded
            log.info("Determining which root modules and versions to load");
            Map<String, Set<Version>> toLoad = getModuleVersionsToLoad(in);

            // 3. Create import entries
            log.info("Creating import entries");
            for(String rootModuleId : toLoad.keySet()) {
                Set<Version> versions = toLoad.get(rootModuleId);
                for(Version version : versions) {
                    String ver = version.getId();
                    Map<String, String> metadata = version.getMetadata();
                    ModuleDependency md = deps.get(rootModuleId).get(ver);
                    Set<Module> modules = new HashSet<Module>();

                    Queue<ModuleDependency> depends = new LinkedList<ModuleDependency>();
                    depends.add(md);

                    while (!depends.isEmpty()) {
                        ModuleDependency d = depends.poll();
                        modules.add(new Module(d.getId(), d.getVersion()));
                        depends.addAll(d.getDependencies());
                    }

                    entries.add(new ImportEntry(rootModuleId, ver, metadata, modules, in));
                }
            }
            log.info("Found "+entries.size()+" entries to import");
        }

        public OntologyInterator(IProgressMonitor monitor) throws ImportException {
            this.monitor = monitor;

            processNext();
        }

        @Override
        public boolean hasNext() {
            return !entries.isEmpty() || !inputs.isEmpty();
        }

        /**
         * @throws RuntimeException in case an {@code ImportException} has occurred.
         */
        @Override
        public Ontology next() throws RuntimeException {
            try {
                if(entries.isEmpty()) processNext();
                ImportEntry entry = entries.remove();
                VersionRows bundle = getBundle(entry);
                String ontologyId = entry.getRootModuleId();
                String ontologyVersion = entry.getRootModuleVersion();

                log.info("Building ontology " + ontologyId + " (" + ontologyVersion + ")");
                OntologyBuilder builder = getOntologyBuilder(bundle, ontologyId, ontologyVersion, entry.getMetadata());
                return builder.build();
            } catch (ImportException e) {
                log.error(e);
                throw new RuntimeException(e);
            } catch (URISyntaxException e) {
                log.error(e);
                throw new RuntimeException(e);
            }
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }

    }

    /**
     * Class that knows how to build an {@link Ontology} from a set of RF2 files.
     *
     * @author Alejandro Metke
     *
     */
    protected class OntologyBuilder {
        protected final VersionRows vr;
        protected final String rootModuleId;
        protected final String rootModuleVersion;

        protected final Map<String, String> primitive = new HashMap<String, String>();
        protected final Map<String, Set<String>> parents = new HashMap<String, Set<String>>();
        protected final Map<String, Set<String>> children = new HashMap<String, Set<String>>();
        protected final Map<String, List<String[]>> rels = new HashMap<String, List<String[]>>();
        protected final Map<String, Map<String, String>> roles = new HashMap<String, Map<String, String>>();
        protected final List<String> lateralizableConcepts = new LinkedList<String>();

        protected final String conceptDefinedId;
        protected final String someId;
        protected final String isAId;
        protected String lateralityId;
        protected final String conceptModelAttId;
        protected final String neverGroupedIdsString;
        protected final String fsnId;
        protected final String synonymId;
        protected final String definitionId;
        protected final String rightIdentityIds;
        protected final String roleGroupId;
        protected final String measurementTypeInt;
        protected final String measurementTypeFloat;
        protected final String equalsOperatorId;
        protected final String unitRoleId;
        protected final Set<String> neverGroupedIds = new HashSet<String>();

        protected final Map<String, Concept> ci = new HashMap<String, Concept>();
        protected final Map<String, NamedRole> ri = new HashMap<String, NamedRole>();
        protected final Map<String, NamedFeature> fi = new HashMap<String, NamedFeature>();
        protected final Collection<Axiom> statedAxioms = new ArrayList<Axiom>();
        protected final Map<String, String> featureType = new HashMap<String, String>();

        protected final Map<String, List<String[]>> cdsMap = new HashMap<String, List<String[]>>();

        public OntologyBuilder(VersionRows vr, String rootModuleId, String rootModuleVersion,
                Map<String, String> metadata) {
            this.vr = vr;
            this.rootModuleId = rootModuleId;
            this.rootModuleVersion = rootModuleVersion;

            conceptDefinedId = metadata.get("conceptDefinedId");
            someId = metadata.get("someId");
            isAId = metadata.get("isAId");
            lateralityId = metadata.get("lateralityId");
            conceptModelAttId = metadata.get("conceptModelAttId");
            neverGroupedIdsString = metadata.get("neverGroupedIds");
            fsnId = metadata.get("fsnId");
            synonymId = metadata.get("synonymId");
            definitionId = metadata.get("definitionId");
            rightIdentityIds = metadata.get("rightIdentityIds");
            roleGroupId = metadata.get("roleGroupId");
            measurementTypeInt = metadata.get("intTypeId");
            measurementTypeFloat = metadata.get("floatTypeId");
            equalsOperatorId = metadata.get("equalsOperatorId");
            unitRoleId = metadata.get("unitRoleId");

            if (conceptDefinedId == null) {
                log.warn("Metadata value for conceptDefinedId was not found. "
                        + "Import process might produce unexpected results.");
            }

            if (someId == null) {
                log.warn("Metadata value for someId was not found. "
                        + "Import process might produce unexpected results.");
            }

            if (isAId == null) {
                log.warn("Metadata value for isAId was not found. "
                        + "Import process might produce unexpected results.");
            }

            if (lateralityId == null) {
                lateralityId = "";
                log.warn("Metadata value for lateralityId was not found. "
                        + "Import process might produce unexpected results.");
            }

            if (conceptModelAttId == null) {
                log.warn("Metadata value for conceptModelAttId was not found. "
                        + "Import process might produce unexpected results.");
            }

            if (neverGroupedIdsString == null) {
                log.warn("Metadata value for neverGroupedIds was not found. "
                        + "Import process might produce unexpected results.");
            } else {
                String[] parts = neverGroupedIdsString.split("[,]");
                for (String part : parts)
                    neverGroupedIds.add(part);
            }

            if (fsnId == null) {
                log.warn("Metadata value for fsnId was not found. "
                        + "Import process might produce unexpected results.");
            }

            if (synonymId == null) {
                log.warn("Metadata value for synonymId was not found. "
                        + "Import process might produce unexpected results.");
            }

            if (definitionId == null) {
                log.warn("Metadata value for definitionId was not found. "
                        + "Import process might produce unexpected results.");
            }

            if (rightIdentityIds == null) {
                log.warn("Metadata value for rightIdentityIds was not found. "
                        + "Import process might produce unexpected results.");
            }

            if (roleGroupId == null) {
                log.warn("Metadata value for roleGroupId was not found. "
                        + "Import process might produce unexpected results.");
            }

            if (measurementTypeFloat == null) {
                log.warn("Metadata value for floatTypeId was not found. "
                        + "Import process might produce unexpected results.");
            }
            if (measurementTypeInt == null) {
                log.warn("Metadata value for intTypeId was not found. "
                        + "Import process might produce unexpected results.");
            }
            if (equalsOperatorId == null) {
                log.warn("Metadata value for equalsOperatorId was not found. "
                        + "Import process might produce unexpected results.");
            }
            if (unitRoleId == null) {
                log.warn("Metadata value for unitRoleId was not found. "
                        + "Import process might produce unexpected results.");
            }
        }

        protected Ontology build() throws URISyntaxException {
            // Process concept rows
            log.info("Processing " + vr.getConceptRows().size() + " concept rows");
            for (ConceptRow cr : vr.getConceptRows()) {
                String id = cr.getId();
                if (isActive(cr.getActive())) {
                    if (!conceptDefinedId.equals(cr.getDefinitionStatusId())) {
                        primitive.put(id, "1");
                    } else {
                        primitive.put(id, "0");
                    }
                }
            }

            // Process relationship rows
            log.info("Processing " + vr.getRelationshipRows().size() + " relationship rows");
            for (RelationshipRow rr : vr.getRelationshipRows()) {
                if (!someId.equals(rr.getModifierId())) {
                    throw new RuntimeException("Only existentials are supported.");
                }

                // only process active concepts and defining relationships
                if (isActive(rr.getActive())) {
                    String type = rr.getTypeId();
                    String src = rr.getSourceId();
                    String dest = rr.getDestinationId();
                    if (isAId.equals(type)) {
                        populateParent(src, dest, parents);
                        populateChildren(dest, src, children);
                    } else {
                        if (lateralityId.equals(type)) {
                            lateralizableConcepts.add(src);
                        }
                        // Populate relationships
                        populateRels(rr.getId(), src, type, dest, rr.getRelationshipGroup(), rels);
                    }
                }
            }

            log.info("Processing " + vr.getConcreteDomainRows().size() + " concrete domain rows");
            Set<String> untypedFeatures = new HashSet<String>();

            for (RefsetRow rr : vr.getConcreteDomainRows()) {
                if (isActive(rr.getActive())) {
                    // 0id 1effectiveTime 2active 3moduleId 4refSetId
                    // 5referencedComponentId 6unitId 7operatorId 8value
                    // &populateCDs( $values[5], $values[4], $values[7],
                    // $values[8], $values[6] );
                    final String[] extras = rr.getExtras();
                    populateCDs(cdsMap, rr.getReferencedComponentId(), rr.getRefsetId(), extras[1], extras[2],
                            extras[0]);
                    // WARNING: Assumes refsets are immediate children
                    if (parents.get(rr.getRefsetId()).contains(measurementTypeFloat)) {
                        featureType.put(rr.getRefsetId(), "float");
                    } else if (parents.get(rr.getRefsetId()).contains(measurementTypeInt)) {
                        featureType.put(rr.getRefsetId(), "int");
                    } else {
                        untypedFeatures.add(rr.getRefsetId());
                    }
                }
            }

            for (String refsetId : untypedFeatures) {
                log.error("ERROR: Could not determine the type (int/float) of " + refsetId);
            }

            log.info("Creating role axioms");
            populateRoles(children.get(conceptModelAttId), "", rightIdentityIds, children, roles);

            // Add role axioms
            for (String r1 : roles.keySet()) {
                String parentRole = roles.get(r1).get("parentrole");

                if (!"".equals(parentRole)) {
                    Role lhs = getRole(r1, ri);
                    Role rhs = getRole(parentRole, ri);
                    statedAxioms.add(new RoleInclusion(new Role[] { lhs }, rhs));
                }

                String rightId = roles.get(r1).get("rightID");
                if (!"".equals(rightId)) {
                    Role lhs1 = getRole(r1, ri);
                    Role lhs2 = getRole(rightId, ri);
                    statedAxioms.add(new RoleInclusion(new Role[] { lhs1, lhs2 }, lhs1));
                }
            }

            // Add concept axioms
            log.info("Creating axioms for " + primitive.size() + " active concepts");
            for (String c1 : primitive.keySet()) {
                Set<String> prs = parents.get(c1);
                int numParents = (prs != null) ? prs.size() : 0;

                List<String[]> relsVal = rels.get(c1);
                int numRels = (relsVal != null) ? 1 : 0;

                List<String[]> cdsVal = cdsMap.get(c1);
                int numCds = (cdsVal != null) ? 1 : 0;

                int numElems = numParents + numRels + numCds;

                if (numParents == 0 && numElems > 0) {
                    log.warn("Root concept " + c1 + " has non-ISA relationships but no ISA relationships.");
                }

                if (numElems == 0) {
                    // do nothing
                } else if (numElems == 1 && numParents == 1) {
                    Concept lhs = getConcept(c1, ci);
                    Concept rhs = getConcept(prs.iterator().next(), ci);
                    statedAxioms.add(new ConceptInclusion(lhs, rhs));
                } else {
                    List<Concept> conjs = new ArrayList<Concept>();

                    // Add parents
                    if (prs != null) {
                        for (String pr : prs) {
                            conjs.add(getConcept(pr, ci));
                        }
                    }

                    // Process concrete domains
                    if (cdsVal != null) {
                        for (String[] datatype : cdsVal) {
                            mapDatatype(conjs, datatype);
                        }
                    }

                    // Process relationships
                    if (relsVal != null) {
                        for (Set<RoleValuePair> rvs : groupRoles(relsVal)) {
                            mapRoles(conjs, rvs);
                        }
                    }

                    final ConceptInclusion axiom = new ConceptInclusion(
                            getConcept(c1, ci), new Conjunction(conjs));
                    statedAxioms.add(axiom);

                    if (primitive.get(c1).equals("0")) {
                        statedAxioms.add(new ConceptInclusion(new Conjunction(
                                conjs), getConcept(c1, ci)));
                    }
                }
            }

            log.info("Finished building ontology");

            return new Ontology(rootModuleId, rootModuleVersion, statedAxioms, null);
        }

        protected boolean isActive(final String active) {
            return "1".equals(active);
        }

        protected void mapRoles(List<Concept> conjs, Set<RoleValuePair> rvs) {
            if (rvs.size() > 1) {
                Concept[] innerConjs = new Concept[rvs.size()];
                int j = 0;
                for (RoleValuePair rv : rvs) {
                    NamedRole role = getRole(rv.role, ri);
                    Concept filler = resolveFiller(getConcept(rv.value, ci), rv.id);
                    Existential exis = new Existential(role, filler);
                    innerConjs[j++] = exis;
                }
                // Wrap with a role group
                conjs.add(new Existential(getRole(roleGroupId, ri), new Conjunction(innerConjs)));
            } else {
                RoleValuePair first = rvs.iterator().next();
                NamedRole role = getRole(first.role, ri);
                Concept filler = resolveFiller(getConcept(first.value, ci), first.id);
                Existential exis = new Existential(role, filler);
                if (neverGroupedIds.contains(first.role)) {
                    // Does not need a role group
                    conjs.add(exis);
                } else {
                    // Needs a role group
                    conjs.add(new Existential(getRole(roleGroupId, ri), exis));
                }
            }
        }

        protected Concept resolveFiller(Concept value, String compId) {
            if (cdsMap.containsKey(compId)) {
                final List<Concept> concepts = new ArrayList<Concept>();
                concepts.add(value);
                for (String[] datatype : cdsMap.get(compId)) {
                    mapDatatype(concepts, datatype);
                }
                final Conjunction result = new Conjunction(concepts);
                if (log.isTraceEnabled()) {
                    log.trace("Mapping CD info: " + result.toString());
                }
                return result;
            } else {
                return value;
            }
        }

        protected void mapDatatype(List<Concept> conjs, String[] datatype) {
            NamedFeature feature = getFeature(datatype[0], fi);
            String type = featureType.get(datatype[0]);

            String operatorId = datatype[1];
            String unitId = datatype[3];

            Literal value;
            if (type.equals("int")) {
                value = new IntegerLiteral(Integer.parseInt(datatype[2]));
            } else if (type.equals("float")) {
                value = new DecimalLiteral(new BigDecimal(datatype[2]));
            } else {
                log.error("Unknown type: " + type);
                return;
            }

            if (equalsOperatorId.equals(operatorId)) {
                Concept[] concepts = {
                        new Existential(getRole(unitRoleId, ri), getConcept(unitId, ci)),
                        new Datatype(feature, Operator.EQUALS, value), };

                conjs.add(new Existential(getRole(roleGroupId, ri), new Conjunction(concepts)));
            } else {
                log.error("Unknown operator: " + operatorId);
            }
        }

        protected void populateInactiveRels(String comp, String src,
                String role, String tgt, String group,
                Map<String, List<String[]>> inactiveRels) {
            List<String[]> val = inactiveRels.get(src);
            if (val == null) {
                val = new ArrayList<String[]>();
                inactiveRels.put(src, val);
            }
            val.add(new String[] { comp, role, tgt, group });
        }

        protected void populateInactiveParent(String src, String tgt,
                Map<String, Set<String>> inactiveParents) {
            Set<String> prs = inactiveParents.get(src);
            if (prs == null) {
                prs = new TreeSet<String>();
                inactiveParents.put(src, prs);
            }
            prs.add(tgt);
        }

        protected void populateInactiveChildren(String src, String tgt,
                Map<String, Set<String>> inactiveChildren) {
            Set<String> prs = inactiveChildren.get(src);
            if (prs == null) {
                prs = new TreeSet<String>();
                inactiveChildren.put(src, prs);
            }
            prs.add(tgt);
        }

        protected void populateInactiveRoles(Set<String> roles,
                String parentSCTID, String version, String rightIdentityIds,
                Map<String, Set<String>> inactiveChildren,
                Map<String, Map<String, String>> inactiveRoles) {
            if (roles == null)
                return;
            for (String role : roles) {
                Set<String> cs = inactiveChildren.get(role);
                if (cs != null) {
                    populateInactiveRoles(cs, role, version, rightIdentityIds,
                            inactiveChildren, inactiveRoles);
                }
                String[] ris = rightIdentityIds.split("[,]");
                String ri = (ris[0].equals(role)) ? ris[1] : null;
                if (ri != null) {
                    populateInactiveRoleDef(role, ri, parentSCTID,
                            inactiveRoles);
                } else {
                    populateInactiveRoleDef(role, "", parentSCTID,
                            inactiveRoles);
                }
            }
        }

        protected void populateInactiveRoleDef(String code, String rightId,
                String parentRole,
                Map<String, Map<String, String>> inactiveRoles) {
            Map<String, String> vals = inactiveRoles.get(code);
            if (vals == null) {
                vals = new HashMap<String, String>();
                inactiveRoles.put(code, vals);
            }
            vals.put("rightID", rightId);
            vals.put("parentrole", parentRole);
        }

    }

}

