/*
 * Apache HTTPD logparsing made easy
 * Copyright (C) 2013 Niels Basjes
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package nl.basjes.parse.core;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import nl.basjes.parse.core.exceptions.CannotChangeDisectorsAfterConstructionException;
import nl.basjes.parse.core.exceptions.DisectionFailure;
import nl.basjes.parse.core.exceptions.FatalErrorDuringCallOfSetterMethod;
import nl.basjes.parse.core.exceptions.InvalidDisectorException;
import nl.basjes.parse.core.exceptions.InvalidFieldMethodSignature;
import nl.basjes.parse.core.exceptions.MissingDisectorsException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Parser<RECORD> {

    private static class DisectorPhase {
        public DisectorPhase(final String inputType, final String outputType, final String name, final Disector instance) {
            this.inputType  = inputType;
            this.outputType = outputType;
            this.name       = name;
            this.instance   = instance;
        }

        private String   inputType;
        private String   outputType;
        private String   name;
        private Disector instance;
    }

    // --------------------------------------------

    private static final Logger LOG = LoggerFactory.getLogger(Parser.class);

    private final Class<RECORD> recordClass;

    private final Set<DisectorPhase> availableDisectors = new HashSet<DisectorPhase>();
    private final Set<Disector> allDisectors = new HashSet<Disector>();

    // Key = "request.time.hour"
    // Value = the set of disectors that must all be started once we have this value
    private Map<String, Set<DisectorPhase>> compiledDisectors = null;
    private Set<String> usefulIntermediateFields = null;
    private String rootType;
    private String rootName;

    // The target methods in the record class that will want to receive the values
    private final Map<String, Set<Method>> targets = new TreeMap<String, Set<Method>>();

    private final Set<String> locatedTargets = new HashSet<String>();

    private boolean usable = false;

    // --------------------------------------------

    public Set<String> getNeeded() {
        return targets.keySet();
    }

    // --------------------------------------------

    public Set<String> getUsefulIntermediateFields() {
        return usefulIntermediateFields;
    }

    // --------------------------------------------

    public final void addDisector(final Disector disector) {
        if (compiledDisectors != null) {
            throw new CannotChangeDisectorsAfterConstructionException();
        }

        final String inputType = disector.getInputType();
        final String[] outputs = disector.getPossibleOutput();

        if (outputs == null || outputs.length == 0){
            return; // If a disector cannot produce any output we are not adding it.
        }

        for (final String output : outputs) {
            final int colonPos = output.indexOf(':');
            final String outputType = output.substring(0, colonPos);
            final String name = output.substring(colonPos + 1);
            availableDisectors.add(new DisectorPhase(inputType, outputType, name, disector));
        }

        allDisectors.add(disector);
    }

    // --------------------------------------------

    public final void dropDisector(Class<? extends Disector> disectorClassToDrop) {
        if (compiledDisectors != null) {
            throw new CannotChangeDisectorsAfterConstructionException();
        }

        Set<DisectorPhase> removePhase = new HashSet<DisectorPhase>();
        for (final DisectorPhase disectorPhase : availableDisectors) {
            if (disectorPhase.instance.getClass().equals(disectorClassToDrop)) {
                removePhase.add(disectorPhase);
            }
        }
        availableDisectors.removeAll(removePhase);

        Set<Disector> removeDisector = new HashSet<Disector>();
        for (final Disector disector : allDisectors) {
            if (disector.getClass().equals(disectorClassToDrop)) {
                removeDisector.add(disector);
            }
        }
        allDisectors.removeAll(removeDisector);
    }

    // --------------------------------------------

    protected void setRootType(final String newRootType) throws MissingDisectorsException, InvalidDisectorException {
        compiledDisectors = null;

        rootType = newRootType;
        rootName = "rootinputline";
    }

    // --------------------------------------------

    private void assembleDisectors() throws MissingDisectorsException, InvalidDisectorException {
        if (compiledDisectors != null) {
            return; // nothing to do.
        }

        // So
        // - we have a set of needed values (targets)
        // - we have a set of disectors that can pick apart some input
        // - we know where to start from
        // - we need to know how to proceed

        // Step 1: Acquire all potentially useful subtargets
        // We first build a set of all possible subtargets that may be useful
        // this way we can skip anything we know not to be useful
        Set<String> needed = new HashSet<String>(getNeeded());
        needed.add(rootType + ':' + rootName);

        Set<String> allPossibleSubtargets = new HashSet<String>();
        for (String need : needed) {
            String neededName = need.substring(need.indexOf(':') + 1);
            LOG.debug("Needed:{}", neededName);
            String[] needs = neededName.split("\\.");
            StringBuilder sb = new StringBuilder(need.length());

            for (String part : needs) {
                if (sb.length() == 0) {
                    sb.append(part);
                } else {
                    sb.append('.').append(part);
                }
                allPossibleSubtargets.add(sb.toString());
                LOG.debug("Possible: {}", sb.toString());
            }
        }

        // Step 2: From the root we explore all possibly useful trees (recursively)
        compiledDisectors        = new HashMap<String, Set<DisectorPhase>>();
        usefulIntermediateFields = new HashSet<String>();
        findUsefulDisectorsFromField(allPossibleSubtargets, rootType, rootName, true);

        // Step 3: Inform all disectors to prepare for the run
        for (Set<DisectorPhase> disectorPhases : compiledDisectors.values()) {
            for (DisectorPhase disectorPhase : disectorPhases) {
                disectorPhase.instance.prepareForRun();
            }
        }
        // Step 4: As a final step we verify that every required input can be found
        Set<String> missingDisectors = getTheMissingFields();
        if (missingDisectors != null && !missingDisectors.isEmpty()) {
            StringBuilder allMissing = new StringBuilder(missingDisectors.size()*64);
            for (String missing:missingDisectors){
                allMissing.append(missing).append(' ');
            }
            throw new MissingDisectorsException(allMissing.toString());
        }

        usable = true;
    }

    // --------------------------------------------

    private void findUsefulDisectorsFromField(
            final Set<String> possibleTargets,
            final String subRootType, final String subRootName,
            final boolean thisIsTheRoot) {

        String subRootId = subRootType + ':' + subRootName;

        LOG.debug("findUsefulDisectors:\"" + subRootType + "\" \"" + subRootName + "\"");

        // When we reach this point we have disectors to get here.
        // So we store this to later validate if we have everything.
        locatedTargets.add(subRootId);

        for (DisectorPhase disector: availableDisectors) {

            if (!(disector.inputType.equals(subRootType))) {
                continue; // Wrong type
            }

            // If it starts with a . it extends.
            // If it doesn't then it starts at the beginning
            Set<String> checkFields = new HashSet<String>();

            // If true then this disector can output any name instead of just one
            boolean isWildCardDisector = disector.name.equals("*");

            if (isWildCardDisector) {
                // Ok, this is special
                // We need to see if any of the wanted types start with the
                // subRootName (it may have a '.' in the rest of the line !)
                String subRootNameMatch = subRootName + '.';
                for (String possibleTarget : possibleTargets) {
                    if (possibleTarget.startsWith(subRootNameMatch)) {
                        checkFields.add(possibleTarget);
                    }
                }
            } else if (thisIsTheRoot) {
                checkFields.add(disector.name);
            } else {
                checkFields.add(subRootName + '.' + disector.name);
            }

            for (String checkField: checkFields) {
                if (possibleTargets.contains(checkField)
                    && !compiledDisectors.containsKey(disector.outputType + ":" + checkField)) {

                    Set<DisectorPhase> subRootPhases = compiledDisectors.get(subRootId);
                    if (subRootPhases == null) {
                        // New so we can simply add it.
                        subRootPhases = new HashSet<DisectorPhase>();
                        compiledDisectors.put(subRootId, subRootPhases);
                        usefulIntermediateFields.add(subRootName);
                    }

                    Class<? extends Disector> clazz = disector.instance.getClass();
                    DisectorPhase disectorPhaseInstance = findDisectorInstance(subRootPhases, clazz);

                    if (disectorPhaseInstance == null) {
                        disectorPhaseInstance =
                                new DisectorPhase(disector.inputType, disector.outputType,
                                        checkField, disector.instance.getNewInstance());
                        subRootPhases.add(disectorPhaseInstance);
                    }

                    // Tell the disector instance what to expect
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Informing : (" + disector.inputType + ")" + subRootName
                                + " --> " + disector.instance.getClass().getName()
                                + " --> (" + disector.outputType + ")" + checkField);
                    }
                    disectorPhaseInstance.instance.prepareForDisect(subRootName, checkField);

                    // Recurse from this point down
                    findUsefulDisectorsFromField(possibleTargets, disector.outputType, checkField, false);
                }
            }
        }
    }

    private DisectorPhase findDisectorInstance(Set<DisectorPhase> disectorPhases,
                                               Class<? extends Disector> clazz) {
        for (DisectorPhase phase : disectorPhases) {
            if (phase.instance.getClass() == clazz) {
                return phase;
            }
        }
        return null;
    }

    // --------------------------------------------

    public Set<String> getTheMissingFields() {
        Set<String> missing = new HashSet<String>();
        for (String target : getNeeded()) {
            if (!locatedTargets.contains(target)) {
                // Handle wildcard targets differently
                if (target.endsWith("*")) {
                    if (target.endsWith(".*")) {
                        if (!locatedTargets.contains(target.substring(0, target.length() - 2))) {
                            missing.add(target);
                        }
                    }
                    // Else: it ends with :* and it is always "present".
                } else {
                    missing.add(target);
                }
            }
        }
        return missing;
    }

    // --------------------------------------------

    /*
     * The constructor tries to retrieve the desired fields from the annotations in the specified class. */
    public Parser(final Class<RECORD> clazz) {
        recordClass = clazz;

        // Get all methods of the correct signature that have been annotated
        // with Field
        for (final Method method : recordClass.getMethods()) {
            final Field field = method.getAnnotation(Field.class);
            if (field != null) {
                addParseTarget(method, field.value());
            }
        }
    }

    // --------------------------------------------

    /*
     * When there is a need to add a target callback manually use this method. */
    public void addParseTarget(final Method method, final String[] fieldValues) {
        if (method == null || fieldValues == null) {
            return; // Nothing to do here
        }

        final Class<?>[] parameters = method.getParameterTypes();
        if (
                ((parameters.length == 1) && (parameters[0] == String.class)) ||
                ((parameters.length == 2) && (parameters[0] == String.class) && (parameters[1] == String.class))
        ) {
            for (final String fieldValue : fieldValues) {
                // We have 1 real target
                Set<Method> fieldTargets = targets.get(fieldValue);
                if (fieldTargets == null) {
                    fieldTargets = new HashSet<Method>();
                }
                fieldTargets.add(method);
                targets.put(fieldValue, fieldTargets);
            }
        } else {
            throw new InvalidFieldMethodSignature(method);
        }

        compiledDisectors = null;
    }

    // --------------------------------------------

    /**
     * Parse the value and return a new instance of RECORD.
     * For this method to work the RECORD class may NOT be an inner class.
     */
    public RECORD parse(final String value)
        throws InstantiationException, IllegalAccessException, DisectionFailure, InvalidDisectorException, MissingDisectorsException {
        assembleDisectors();
        final Parsable<RECORD> parsable = createParsable();
        if (parsable == null) {
            return null;
        }
        parsable.setRootDisection(rootType, rootName, value);
        return parse(parsable).getRecord();
    }

    // --------------------------------------------

    /**
     * Parse the value and call all configured setters in the provided instance of RECORD.
     */
    public RECORD parse(final RECORD record, final String value)
        throws InstantiationException, IllegalAccessException, DisectionFailure, InvalidDisectorException, MissingDisectorsException {
        assembleDisectors();
        final Parsable<RECORD> parsable = createParsable(record);
        parsable.setRootDisection(rootType, rootName, value);
        return parse(parsable).getRecord();
    }

    // --------------------------------------------

    Parsable<RECORD> parse(final Parsable<RECORD> parsable)
        throws DisectionFailure, InvalidDisectorException, MissingDisectorsException {
        assembleDisectors();

        if (!usable) {
            return null;
        }

        // Values look like "TYPE:foo.bar"
        Set<ParsedField> toBeParsed = new HashSet<ParsedField>(parsable.getToBeParsed());

        while (toBeParsed.size() > 0) {
            for (ParsedField fieldThatNeedsToBeParsed : toBeParsed) {
                parsable.setAsParsed(fieldThatNeedsToBeParsed);
                Set<DisectorPhase> disectorSet = compiledDisectors.get(fieldThatNeedsToBeParsed.getId());
                if (disectorSet != null) {
                    for (DisectorPhase disector : disectorSet) {
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("Disect " + fieldThatNeedsToBeParsed + " with " + disector.instance.getClass().getName());
                        }
                        disector.instance.disect(parsable, fieldThatNeedsToBeParsed.getName());
                    }
                } else {
                    LOG.trace("NO DISECTORS FOR \"{}\"", fieldThatNeedsToBeParsed);
                }
            }
            toBeParsed.clear();
            toBeParsed.addAll(parsable.getToBeParsed());
        }
        return parsable;
    }

    // --------------------------------------------

    RECORD store(final RECORD record, final String key, final String name, final String value) {
        final Set<Method> methods = targets.get(key);
        if (methods == null) {
            LOG.error("NO methods for \""+key+"\"");
        } else {
            for (Method method : methods) {
                if (method != null) {
                    try {
                        if (method.getParameterTypes().length == 2) {
                            method.invoke(record, name, value);
                        } else {
                            method.invoke(record, value);
                        }
                    } catch (final Exception e) {
                        throw new FatalErrorDuringCallOfSetterMethod(e.getMessage() + " caused by \"" +
                                                                     e.getCause() + "\" when calling \"" +
                                                                     method.toGenericString() + "\" for " +
                                                                     " key = \"" + key + "\" " +
                                                                     " name = \"" + name + "\" " +
                                                                     " value = \"" + value + "\"" );
                    }
                }
            }
        }
        return record;
    }

    // --------------------------------------------

    Parsable<RECORD> createParsable(RECORD record) {
        return new Parsable<RECORD>(this, record);
    }

    public Parsable<RECORD> createParsable() throws InstantiationException, IllegalAccessException {
        RECORD record;

        try {
            Constructor<RECORD> co = recordClass.getConstructor();
            record = co.newInstance();
        } catch (Exception e) {
            LOG.error("Unable to create instance: " + e.toString());
            return null;
        }
        return createParsable(record);
    }

    // --------------------------------------------

    /**
     * This method is for use by the developer to query the parser about
     * the possible paths that may be extracted.
     * @return A list of all possible paths that could be determined automatically.
     * @throws InvalidDisectorException
     * @throws MissingDisectorsException
     */
    public List<String> getPossiblePaths() throws MissingDisectorsException, InvalidDisectorException {
        return getPossiblePaths(15);
    }

    /**
     * This method is for use by the developer to query the parser about
     * the possible paths that may be extracted.
     * @param maxDepth The maximum recursion depth
     * @return A list of all possible paths that could be determined automatically.
     * @throws InvalidDisectorException
     * @throws MissingDisectorsException
     */
    public List<String> getPossiblePaths(int maxDepth) throws MissingDisectorsException, InvalidDisectorException {
        assembleDisectors();

        if (allDisectors == null) {
            return null; // nothing to do.
        }

        List<String> paths = new ArrayList<String>();

        Map<String, String[]> pathNodes = new HashMap<String, String[]>();

        for (Disector disector : allDisectors) {
            final String inputType = disector.getInputType();
            final String[] outputs = disector.getPossibleOutput();
            pathNodes.put(inputType, outputs);
        }

        findAdditionalPossiblePaths(pathNodes, paths, "", rootType, maxDepth);

        return paths;
    }

    /**
     * Add all child paths in respect to the base (which is already present in the result set)
     */
    private void findAdditionalPossiblePaths(Map<String, String[]> pathNodes, List<String> paths, String base, String baseType,
            int maxDepth) {
        if (maxDepth == 0) {
            return;
        }

        if (pathNodes.containsKey(baseType)) {
            String[] childPaths = pathNodes.get(baseType);
            for (String childPath : childPaths) {
                final int colonPos = childPath.indexOf(':');
                final String childType = childPath.substring(0, colonPos);
                final String childName = childPath.substring(colonPos + 1);

                String childBase;
                if (base.isEmpty()) {
                    childBase = childName;
                } else {
                    childBase = base + '.' + childName;
                }
                paths.add(childType+':'+childBase);

                findAdditionalPossiblePaths(pathNodes, paths, childBase, childType, maxDepth - 1);
            }
        }
    }

    // --------------------------------------------

}
