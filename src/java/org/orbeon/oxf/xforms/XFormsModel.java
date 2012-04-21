/**
 * Copyright (C) 2010 Orbeon, Inc.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation; either version
 * 2.1 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.xforms;

import org.apache.log4j.Logger;
import org.dom4j.Document;
import org.dom4j.Element;
import org.orbeon.oxf.common.OXFException;
import org.orbeon.oxf.common.ValidationException;
import org.orbeon.oxf.pipeline.api.ExternalContext;
import org.orbeon.oxf.pipeline.api.PipelineContext;
import org.orbeon.oxf.processor.ProcessorImpl;
import org.orbeon.oxf.resources.URLFactory;
import org.orbeon.oxf.util.*;
import org.orbeon.oxf.xforms.analysis.ElementAnalysis;
import org.orbeon.oxf.xforms.analysis.model.Instance;
import org.orbeon.oxf.xforms.analysis.model.Model;
import org.orbeon.oxf.xforms.analysis.model.Submission;
import org.orbeon.oxf.xforms.event.*;
import org.orbeon.oxf.xforms.event.EventListener;
import org.orbeon.oxf.xforms.event.events.*;
import org.orbeon.oxf.xforms.function.xxforms.XXFormsExtractDocument;
import org.orbeon.oxf.xforms.model.DataModel;
import org.orbeon.oxf.xforms.model.XFormsModelAction;
import org.orbeon.oxf.xforms.submission.BaseSubmission;
import org.orbeon.oxf.xforms.submission.XFormsModelSubmission;
import org.orbeon.oxf.xforms.xbl.Scope;
import org.orbeon.oxf.xforms.xbl.XBLContainer;
import org.orbeon.oxf.xml.TransformerUtils;
import org.orbeon.oxf.xml.dom4j.Dom4jUtils;
import org.orbeon.oxf.xml.dom4j.ExtendedLocationData;
import org.orbeon.oxf.xml.dom4j.LocationData;
import org.orbeon.saxon.om.*;
import org.orbeon.saxon.trans.XPathException;
import org.orbeon.saxon.value.Value;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;

/**
 * Represents an XForms model.
 */
public class XFormsModel implements XFormsEventTarget, XFormsEventObserver, XFormsObjectResolver {

    public static final String LOGGING_CATEGORY = "model";
    public static final Logger logger = LoggerFactory.createLogger(XFormsModel.class);
    public final IndentedLogger indentedLogger;

    // Static representation of this model
    private final Model staticModel;

    // Model attributes
    private String effectiveId; // not final because can change if model within repeat iteration

    // Instances
    private final List<String> instanceIds;
    private final List<XFormsInstance> instances;
    private final Map<String, XFormsInstance> instancesMap;

    // Submissions and actions
    private final Map<String, XFormsModelSubmission> submissions;
    private final Map<String, XFormsModelAction> actions = new HashMap<String, XFormsModelAction>();

    // Context and variables
    private BindingContext defaultEvaluationContext;
    private Map<String, ValueRepresentation> topLevelVariables = new LinkedHashMap<String, ValueRepresentation>();

    // Binds
    private final XFormsModelBinds binds;
    private final boolean mustBindValidate;

    // Schema validation
    private XFormsModelSchemaValidator schemaValidator;
    private boolean hasSchema;

    // Container
    private final XBLContainer container;
    private final XFormsContextStack contextStack;    // context stack for evaluation, used by binds, submissions, event handlers

    // Containing document
    private final XFormsContainingDocument containingDocument;

    public XFormsModel(XBLContainer container, String effectiveId, Model staticModel) {

        // Remember static model
        this.staticModel = staticModel;

        // Set container
        this.container = container;
        this.containingDocument = container.getContainingDocument();

        this.indentedLogger = containingDocument.getIndentedLogger(LOGGING_CATEGORY);

        this.effectiveId = effectiveId;

        // Extract list of instances ids
        {
            final Collection<Instance> staticInstances = staticModel.instancesMap().values();
            if (staticInstances.isEmpty()) {
                // No instance in this model
                instanceIds = Collections.emptyList();
                instances = Collections.emptyList();
                instancesMap = Collections.emptyMap();
            } else {
                // At least one instance in this model
                instanceIds = new ArrayList<String>(staticInstances.size());
                for (final Instance instance : staticInstances)
                    instanceIds.add(instance.staticId());
                instances = Arrays.asList(new XFormsInstance[staticInstances.size()]);
                instancesMap = new HashMap<String, XFormsInstance>(staticInstances.size());
            }
        }

        // Get submissions
        {
            final List<Submission> staticSubmissions = staticModel.jSubmissions();
            if (staticSubmissions.isEmpty()) {
                // No submission in this model
                submissions = Collections.emptyMap();
            } else {
                // At least one submission in this model
                submissions = new HashMap<String, XFormsModelSubmission>();
                for (final Submission staticSubmission : staticSubmissions)
                    submissions.put(staticSubmission.staticId(), new XFormsModelSubmission(this.container, staticSubmission, this));
            }
        }

        // Get all event handlers
        for (final EventHandlerImpl staticEventHandler : staticModel.jEventHandlers()) {
            final ElementAnalysis staticParent = staticEventHandler.parent().get();

            final XFormsEventObserver parent;
            if (staticParent instanceof Submission) {
                parent = submissions.get(staticParent.staticId());
            } else {
                parent = XFormsModel.this;
            }

            actions.put(staticEventHandler.staticId(), new XFormsModelAction(parent, staticEventHandler));
        }

        // Create binds object
        binds = XFormsModelBinds.create(this);
        mustBindValidate = binds != null;

        // Create context stack
        this.contextStack = new XFormsContextStack(this);
    }

    // Evaluate all top-level variables
    public void resetAndEvaluateVariables() {

        // NOTE: This method is called during RRR and by submission processing. Need to do dependency handling.

        // Reset context to this model, including evaluating the model variables
        contextStack.resetBindingContext(this);

        // Remember context and variables
        defaultEvaluationContext = contextStack.getCurrentBindingContext();
        topLevelVariables = contextStack.getCurrentBindingContext().getInScopeVariables(false);
    }

    // Return the value of the given model variable
    public SequenceIterator getVariable(String variableName) throws XPathException {
        return Value.asIterator(topLevelVariables.get(variableName));
    }

    public void updateEffectiveId(String effectiveId) {
        this.effectiveId = effectiveId;

        // NOTE: We shouldn't even be called if the parent control is not relevant.
        if (container.isRelevant()) {
            // Update effective ids of all nested instances
            for (final XFormsInstance currentInstance: instances) {
                // NOTE: we pass the new model id, not the instance id
                currentInstance.updateModelEffectiveId(effectiveId);
            }
        }
    }

    public String getPrefixedId() {
        return staticModel.prefixedId();
    }

    public IndentedLogger getIndentedLogger() {
        return indentedLogger;
    }

    public XFormsContextStack getContextStack() {
        return contextStack;
    }

    public XBLContainer getXBLContainer() {
        return container;
    }

    public XBLContainer getXBLContainer(XFormsContainingDocument containingDocument) {
        return getXBLContainer();
    }

    public XFormsContainingDocument getContainingDocument() {
        return containingDocument;
    }

    public Model getStaticModel() {
        return staticModel;
    }

    /**
     * Get object with the id specified.
     */
    public Object getObjectByEffectiveId(String effectiveId) {

        // If prefixes or suffixes don't match, object can't be found here
        if (!getXBLContainer().getFullPrefix().equals(XFormsUtils.getEffectiveIdPrefix(effectiveId))
                || !XFormsUtils.getEffectiveIdSuffix(getXBLContainer().getEffectiveId()).equals(XFormsUtils.getEffectiveIdSuffix(effectiveId))) {
            return null;
        }

        // Find by static id
        return resolveObjectById(null, XFormsUtils.getStaticIdFromId(effectiveId), null);
    }

    /**
     * Resolve an object. This optionally depends on a source, and involves resolving whether the source is within a
     * repeat or a component.
     *
     * @param sourceEffectiveId  effective id of the source, or null
     * @param targetStaticId     static id of the target
     * @param contextItem        context item, or null (used for bind resolution only)
     * @return                   object, or null if not found
     */
    public Object resolveObjectById(String sourceEffectiveId, String targetStaticId, Item contextItem) {

        if (XFormsUtils.isEffectiveId(targetStaticId))
            throw new OXFException("Target id must be static id: " + targetStaticId);

        // Check this id
        if (targetStaticId.equals(getId()))
            return this;

        // Search instances
        final XFormsInstance instance = instancesMap.get(targetStaticId);
        if (instance != null)
            return instance;

        // Search submissions
        if (submissions != null) {
            final XFormsModelSubmission resultSubmission = submissions.get(targetStaticId);
            if (resultSubmission != null)
                return resultSubmission;
        }

        // Search actions
        {
            final XFormsModelAction action = actions.get(targetStaticId);
            if (action != null)
                return action;
        }

        // Search binds
        if (binds != null) {
            final XFormsModelBinds.Bind bind = binds.resolveBind(targetStaticId, contextItem);
            if (bind != null)
                return bind;
        }

        return null;
    }

    /**
     * Return the default instance for this model, i.e. the first instance. Return null if there is
     * no instance in this model.
     *
     * @return  XFormsInstance or null
     */
    public XFormsInstance getDefaultInstance() {
        return ! instances.isEmpty() ? instances.get(0) : null;
    }

    /**
     * Return all XFormsInstance objects for this model, in the order they appear in the model.
     */
    public List<XFormsInstance> getInstances() {
        return instances;
    }

    /**
     * Return the XFormsInstance with given id, null if not found.
     */
    public XFormsInstance getInstance(String instanceStaticId) {
        return instancesMap.get(instanceStaticId);
    }

    /**
     * Return the XFormsInstance object containing the given node.
     */
    public XFormsInstance getInstanceForNode(NodeInfo nodeInfo) {

        final DocumentInfo documentInfo = nodeInfo.getDocumentRoot();

        // NOTE: We shouldn't even be called if the parent control is not relevant.
        if (container.isRelevant()) {
            for (final XFormsInstance currentInstance: instances) {
                if (currentInstance.documentInfo().isSameNodeInfo(documentInfo))
                    return currentInstance;
            }
        }

        return null;
    }

    /**
     * Set an instance document for this model. There may be multiple instance documents. Each instance document may
     * have an associated id that identifies it.
     */
    public XFormsInstance setInstanceDocument(Object instanceDocument, String modelEffectiveId, String instanceStaticId, String instanceSourceURI,
                                              Connection.Credentials credentials, boolean cached, long timeToLive, String validation, boolean handleXInclude) {

        // Prepare and set instance
        final int instancePosition = instanceIds.indexOf(instanceStaticId);
        final XFormsInstance newInstance;
        {
            final boolean exposeXPathTypes = XFormsProperties.isExposeXPathTypes(containingDocument);
            if (instanceDocument instanceof Document) {
                newInstance = new XFormsInstance(instanceStaticId, modelEffectiveId, instanceSourceURI,
                        credentials, cached, timeToLive, null, false, validation, handleXInclude, exposeXPathTypes,
                        XFormsInstance.wrapDocument((Document) instanceDocument, exposeXPathTypes), false);
            } else if (instanceDocument instanceof DocumentInfo) {
                newInstance = new XFormsInstance(instanceStaticId, modelEffectiveId, instanceSourceURI,
                        credentials, cached, timeToLive, null, true, validation, handleXInclude, exposeXPathTypes,
                        (DocumentInfo) instanceDocument, false);
            } else {
                throw new OXFException("Invalid type for instance document: " + instanceDocument.getClass().getName());
            }
        }
        instances.set(instancePosition, newInstance);

        // Create mapping instance id -> instance
        if (instanceStaticId != null)
            instancesMap.put(instanceStaticId, newInstance);

        return newInstance;
    }

    /**
     * Set an instance. The id of the instance must exist in the model.
     *
     * @param instance          XFormsInstance to set
     * @param replaced          whether this is an instance replacement (as result of a submission)
     */
    public void setInstance(XFormsInstance instance, boolean replaced) {

        // Mark the instance as replaced if needed
        instance.setReplaced(replaced);

        // Prepare and set instance
        final String instanceId = instance.staticId();// use static id as instanceIds contains static ids
        final int instancePosition = instanceIds.indexOf(instanceId);

        instances.set(instancePosition, instance);

        // Create mapping instance id -> instance
        instancesMap.put(instanceId, instance);
    }

    public String getId() {
        return staticModel.staticId();
    }

    public String getEffectiveId() {
        return effectiveId;
    }

    public Scope getScope(XFormsContainingDocument containingDocument) {
        return staticModel.scope();
    }

    public Scope getResolutionScope() {
        return container.getPartAnalysis().scopeForPrefixedId(getPrefixedId());
    }

    public LocationData getLocationData() {
        return staticModel.locationData();
    }

    public XFormsModelBinds getBinds() {
        return binds;
    }

    private void loadSchemasIfNeeded() {
        if (schemaValidator == null) {
            final Element modelElement = staticModel.element();
            schemaValidator = new XFormsModelSchemaValidator(modelElement, indentedLogger);
            schemaValidator.loadSchemas(containingDocument);

            hasSchema = schemaValidator.hasSchema();
        }
    }

    public boolean hasSchema() {
        return hasSchema;
    }

    public XFormsModelSchemaValidator getSchemaValidator() {
        return schemaValidator;
    }

    public String[] getSchemaURIs() {
        if (hasSchema) {
            return schemaValidator.getSchemaURIs();
        } else {
            return null;
        }
    }

    /**
     * Restore the state of the model when the model object was just recreated.
     */
    public void restoreState() {
        // Ensure schema are loaded
        loadSchemasIfNeeded();

        // Restore instances
        restoreInstances();

        // Refresh binds, but do not recalculate (only evaluate "computed expression binds")
        deferredActionContext.rebuild = true;
        deferredActionContext.revalidate = true;

        doRebuild();
        if (binds != null)
            binds.applyComputedExpressionBinds();
        doRevalidate();
    }

    /**
     * Restore all the instances serialized as children of the given container element.
     */
    private void restoreInstances() {

        // Find serialized instances from context
        final List<XFormsInstance> contextInstances = (List<XFormsInstance>) PipelineContext.get().getAttribute(XBLContainer.XFORMS_DYNAMIC_STATE_RESTORE_INSTANCES);

        // Get instances from dynamic state first
        if (contextInstances != null) {
            for (final XFormsInstance instance : contextInstances) {
                // Check that the instance belongs to this model
                if (effectiveId.equals(instance.modelEffectiveId())) {
                    final boolean isReadonlyHint = instance.readonly();
                    // NOTE: Here instance must contain document
                    setInstanceLoadFromCacheIfNecessary(isReadonlyHint, instance, null);
                    indentedLogger.logDebug("restore", "restoring instance from dynamic state", "model effective id", effectiveId, "instance static id", instance.staticId());
                }
            }
        }

        // Then get instances from static state if necessary
        // This can happen if the instance is inline, readonly, and not replaced
        for (final Instance instance : getXBLContainer().getPartAnalysis().getInstances(getPrefixedId())) {
            final Element containerElement = instance.element();
            final String instanceStaticId = instance.staticId();

            if (instancesMap.get(instanceStaticId) == null) {
                // Must create instance

                final String xxformsExcludeResultPrefixes = containerElement.attributeValue(XFormsConstants.XXFORMS_EXCLUDE_RESULT_PREFIXES);
                final List<Element> children = Dom4jUtils.elements(containerElement);

                final boolean isReadonlyHint = XFormsInstance.isReadonlyHint(containerElement);
                final String xxformsValidation = containerElement.attributeValue(XFormsConstants.XXFORMS_VALIDATION_QNAME);

                // Extract document
                final Object instanceDocument = XXFormsExtractDocument.extractDocument(containingDocument.getStaticState().xpathConfiguration(),
                        children.get(0), xxformsExcludeResultPrefixes, isReadonlyHint);

                // Set instance and associated information
                setInstanceDocument(instanceDocument, effectiveId, instanceStaticId, null, null, false, -1, xxformsValidation, false);
            }
        }
    }

    public void performDefaultAction(XFormsEvent event) {
        final String eventName = event.getName();
        if (XFormsEvents.XFORMS_MODEL_CONSTRUCT.equals(eventName)) {
            // 4.2.1 The xforms-model-construct Event
            // Bubbles: Yes / Cancelable: No / Context Info: None
            doModelConstruct();
        } else if (XFormsEvents.XXFORMS_READY.equals(eventName)) {
            // This is called after xforms-ready events have been dispatched to all models
            doAfterReady();
        } else if (XFormsEvents.XFORMS_MODEL_CONSTRUCT_DONE.equals(eventName)) {
            // 4.2.2 The xforms-model-construct-done Event
            // Bubbles: Yes / Cancelable: No / Context Info: None
            // TODO: implicit lazy instance construction
        } else if (XFormsEvents.XFORMS_REBUILD.equals(eventName)) {
            // 4.3.7 The xforms-rebuild Event
            // Bubbles: Yes / Cancelable: Yes / Context Info: None
            doRebuild();
        } else if (XFormsEvents.XFORMS_RECALCULATE.equals(eventName)) {
            // 4.3.6 The xforms-recalculate Event
            // Bubbles: Yes / Cancelable: Yes / Context Info: None
            final XFormsRecalculateEvent recalculateEvent = (XFormsRecalculateEvent) event;
            doRecalculate(recalculateEvent.isApplyInitialValues());
        } else if (XFormsEvents.XFORMS_REVALIDATE.equals(eventName)) {
            // 4.3.5 The xforms-revalidate Event
            // Bubbles: Yes / Cancelable: Yes / Context Info: None
            doRevalidate();
        } else if (XFormsEvents.XFORMS_REFRESH.equals(eventName)) {
            // 4.3.4 The xforms-refresh Event
            // Bubbles: Yes / Cancelable: Yes / Context Info: None
            doRefresh();
        } else if (XFormsEvents.XFORMS_RESET.equals(eventName)) {
            // 4.3.8 The xforms-reset Event
            // Bubbles: Yes / Cancelable: Yes / Context Info: None
            doReset();
        } else if (XFormsEvents.XFORMS_COMPUTE_EXCEPTION.equals(eventName) || XFormsEvents.XFORMS_LINK_EXCEPTION.equals(eventName)) {
            // 4.5.4 The xforms-compute-exception Event
            // Bubbles: Yes / Cancelable: No / Context Info: Implementation-specific error string.
            // The default action for this event results in the following: Fatal error.

            // 4.5.2 The xforms-link-exception Event
            // Bubbles: Yes / Cancelable: No / Context Info: The URI that failed to load (xsd:anyURI)
            // The default action for this event results in the following: Fatal error.

            final XFormsExceptionEvent exceptionEvent = (XFormsExceptionEvent) event;
            final Throwable throwable = exceptionEvent.getThrowable();
            if (throwable instanceof RuntimeException)
                throw (RuntimeException) throwable;
            else
                throw new ValidationException("Received fatal error event: " + eventName, throwable, getLocationData());
        } else if (XFormsEvents.XXFORMS_XPATH_ERROR.equals(eventName)) {
            // Custom event for XPath errors
            // NOTE: We don't like this event very much as it is dispatched in the middle of rebuild/recalculate/revalidate,
            // and event handlers for this have to be careful. It might be better to dispatch it *after* RRR.

            final XXFormsXPathErrorEvent ev = (XXFormsXPathErrorEvent) event;
            final Throwable t = ev.throwable();
            if (isIgnorableXPathError(t))
                XFormsError.logNonFatalXPathErrorAsDebug(containingDocument, t);
            else
                XFormsError.handleNonFatalXPathError(containingDocument, t);
        } else if (XFormsEvents.XXFORMS_BINDING_ERROR.equals(eventName)) {
            // Custom event for binding errors
            // NOTE: We don't like this event very much as it is dispatched in the middle of rebuild/recalculate/revalidate,
            // and event handlers for this have to be careful. It might be better to dispatch it *after* RRR.

            final XXFormsBindingErrorEvent ev = (XXFormsBindingErrorEvent) event;
            XFormsError.handleNonFatalSetvalueError(containingDocument, ev.locationData(), ev.reason());
        }
    }

    private boolean isIgnorableXPathError(Throwable t) {
        if (XFormsProperties.isIgnoreDynamicMIPXPathErrors(containingDocument)) {
            final Throwable root = OXFException.getRootThrowable(t);
            return (root instanceof XPathException) && ! ((XPathException) root).isStaticError();
        } else
            return false;
    }

    private void doReset() {
        // TODO
        // "The instance data is reset to the tree structure and values it had immediately
        // after having processed the xforms-ready event."

        // "Then, the events xforms-rebuild, xforms-recalculate, xforms-revalidate and
        // xforms-refresh are dispatched to the model element in sequence."
        container.dispatchEvent(new XFormsRebuildEvent(containingDocument, XFormsModel.this));
        container.dispatchEvent(new XFormsRecalculateEvent(containingDocument, XFormsModel.this));
        container.dispatchEvent(new XFormsRevalidateEvent(containingDocument, XFormsModel.this));
        container.dispatchEvent(new XFormsRefreshEvent(containingDocument, XFormsModel.this));
    }

    private void doAfterReady() {
    }

    private void doModelConstruct() {
        final Element modelElement = staticModel.element();

        // 1. All XML Schema loaded (throws xforms-link-exception)

        try {
            loadSchemasIfNeeded();
        } catch (Exception e) {
            final String schemaAttribute = modelElement.attributeValue(XFormsConstants.SCHEMA_QNAME);
            container.dispatchEvent(new XFormsLinkExceptionEvent(containingDocument, XFormsModel.this,
                    schemaAttribute, modelElement, e));
        }

        // 2. Create XPath data model from instance (inline or external) (throws xforms-link-exception)
        //    Instance may not be specified.

        {
            // Build initial instance documents

            // Iterate through all instances
            int instancePosition = 0;
            for (final Instance instance : staticModel.instancesMap().values()) {
                // Skip processing in case somebody has already set this particular instance
                if (instances.get(instancePosition++) == null) {
                    // Load instance. This might throw an exception event (and therefore a Java exception) in case of fatal problem.
                    loadInstance(instance.staticId());
                }
            }
        }

        // 3. P3P (N/A)

        // 4. Instance data is constructed. Evaluate binds:
        //    a. Evaluate nodeset
        //    b. Apply model item properties on nodes
        //    c. Throws xforms-binding-exception if the node has already model item property with same name
        // TODO: a, b, c

        // 5. xforms-rebuild, xforms-recalculate, xforms-revalidate
        deferredActionContext.markStructuralChange();

        doRebuild();
        doRecalculate(false);
        doRevalidate();
    }

    private void setInstanceLoadFromCacheIfNecessary(boolean readonlyHint, XFormsInstance newInstance, Element locationDataElement) {
        if (newInstance.documentInfo() == null && newInstance.sourceURI() != null) {
            // Instance not initialized yet and must be loaded from URL

            // This means that the instance was cached
            if (!newInstance.cache())
                throw new ValidationException("Non-initialized instance has to be cacheable for id: " + newInstance.getEffectiveId(),
                        (locationDataElement != null) ? (LocationData) locationDataElement.getData() : getLocationData());

            if (indentedLogger.isDebugEnabled())
                indentedLogger.logDebug("restore", "using instance from instance cache (instance was not initialized)",
                        "id", newInstance.getEffectiveId());

            // NOTE: No XInclude supported to read instances with @src for now
            // TODO: must pass method and request body in case of POST/PUT
            final XFormsInstance cachedInstance
                    = XFormsServerSharedInstancesCache.instance().findConvert(indentedLogger,
                        newInstance.staticId(), newInstance.modelEffectiveId(), newInstance.sourceURI(), newInstance.requestBodyHash(), readonlyHint, false,
                        XFormsProperties.isExposeXPathTypes(containingDocument), newInstance.timeToLive(), newInstance.validation(), INSTANCE_LOADER);

            setInstance(cachedInstance, newInstance.replaced());
        } else {
            // Instance is initialized, just use it

            // This means that the instance was not cached
            if (newInstance.cache())
                throw new ValidationException("Initialized instance has to be non-cacheable for id: " + newInstance.getEffectiveId(),
                        (locationDataElement != null) ? (LocationData) locationDataElement.getData() : getLocationData());

            if (indentedLogger.isDebugEnabled())
                indentedLogger.logDebug("restore", "using initialized instance from state",
                        "id", newInstance.getEffectiveId());

            setInstance(newInstance, newInstance.replaced());
        }
    }

    private void loadInstance(String instanceStaticId) {

        final Instance instance = staticModel.instancesMap().get(instanceStaticId);
        final Element instanceContainer = instance.element();

        indentedLogger.startHandleOperation("load", "loading instance", "instance id", instance.staticId());
        {
            // Get instance resource URI, can be from @src or @resource

            final List<Element> children = Dom4jUtils.elements(instanceContainer);
            if (instance.src() != null) {
                // "If the src attribute is given, then it takes precedence over inline content and the resource
                // attribute, and the XML data for the instance is obtained from the link."

                if (instance.src().equals("")) {
                    // Got a blank src attribute, just dispatch xforms-link-exception
                    final LocationData extendedLocationData = new ExtendedLocationData(instance.locationData(), "processing XForms instance", instanceContainer);
                    final Throwable throwable = new ValidationException("Invalid blank URL specified for instance/@src: " + instanceStaticId, extendedLocationData);
                    container.dispatchEvent(new XFormsLinkExceptionEvent(containingDocument, XFormsModel.this, instance.src(), instanceContainer, throwable));
                }

                // Load instance
                loadExternalInstance(instance, instance.src());
            } else if (children != null && children.size() >= 1) {
                // "If the src attribute is omitted, then the data for the instance is obtained from inline content if
                // it is given or the resource attribute otherwise. If both the resource attribute and inline content
                // are provided, the inline content takes precedence."

                final String xxformsExcludeResultPrefixes = instanceContainer.attributeValue(XFormsConstants.XXFORMS_EXCLUDE_RESULT_PREFIXES);

                if (children.size() > 1) {
                    final LocationData extendedLocationData = new ExtendedLocationData(instance.locationData(), "processing XForms instance", instanceContainer);
                    final Throwable throwable = new ValidationException("xforms:instance element must contain exactly one child element", extendedLocationData);
                    container.dispatchEvent(new XFormsLinkExceptionEvent(containingDocument, XFormsModel.this, null, instanceContainer, throwable));
                }

                try {
                    // Extract document
                    final Object instanceDocument
                            = XXFormsExtractDocument.extractDocument(containingDocument.getStaticState().xpathConfiguration(),
                            (Element) children.get(0), xxformsExcludeResultPrefixes, instance.isReadonlyHint());

                    // Set instance and associated information if everything went well
                    // NOTE: No XInclude supported to read instances with @src for now
                    setInstanceDocument(instanceDocument, effectiveId, instanceStaticId, null, null, false, -1, instance.xxformsValidation(), false);
                } catch (Exception e) {
                    final LocationData extendedLocationData = new ExtendedLocationData(instance.locationData(), "processing XForms instance", instanceContainer);
                    final Throwable throwable = new ValidationException("Error extracting or setting inline instance", extendedLocationData);
                    container.dispatchEvent(new XFormsLinkExceptionEvent(containingDocument, XFormsModel.this, null, instanceContainer, throwable));
                }
            } else if (instance.resource() != null) {
                // "the data for the instance is obtained from inline content if it is given or the
                // resource attribute otherwise"

                if (instance.resource().equals("")) {
                    // Got a blank src attribute, just dispatch xforms-link-exception
                    final LocationData extendedLocationData = new ExtendedLocationData(instance.locationData(), "processing XForms instance", instanceContainer);
                    final Throwable throwable = new ValidationException("Invalid blank URL specified for instance/@resource: " + instanceStaticId, extendedLocationData);
                    container.dispatchEvent(new XFormsLinkExceptionEvent(containingDocument, XFormsModel.this, instance.resource(), instanceContainer, throwable));
                }

                // Load instance
                loadExternalInstance(instance, instance.resource());
            } else {
                // Everything missing
                final LocationData extendedLocationData = new ExtendedLocationData(instance.locationData(), "processing XForms instance", instanceContainer);
                final Throwable throwable = new ValidationException("Required @src attribute, @resource attribute, or inline content for instance: " + instanceStaticId, extendedLocationData);
                container.dispatchEvent(new XFormsLinkExceptionEvent(containingDocument, XFormsModel.this, "", instanceContainer, throwable));
            }
        }
        indentedLogger.endHandleOperation();
    }

    private void loadExternalInstance(Instance instance, String instanceResource) {
        try {
            if (instance.isCacheHint() && ProcessorImpl.getProcessorInputSchemeInputName(instanceResource) == null) {
                // Instance 1) has cache hint and 2) is not input:*, so it can be cached
                // NOTE: We don't allow sharing for input:* URLs as the data will likely differ per request

                // TODO: This doesn't handle optimized submissions.

                final String resolvedInstanceURL = XFormsUtils.resolveServiceURL(containingDocument, instance.element(), instance.instanceSource(),
                        ExternalContext.Response.REWRITE_MODE_ABSOLUTE);

                // NOTE: No XInclude supported to read instances with @src for now
                final XFormsInstance sharedXFormsInstance
                        = XFormsServerSharedInstancesCache.instance().findConvert(indentedLogger,
                            instance.staticId(), effectiveId,
                            resolvedInstanceURL, null, instance.isReadonlyHint(), false, XFormsProperties.isExposeXPathTypes(containingDocument),
                            instance.xxformsTimeToLive(), instance.xxformsValidation(), INSTANCE_LOADER);

                setInstance(sharedXFormsInstance, false);
            } else {
                // Instance cannot be cached

                // NOTE: Optimizing with include() for servlets has limitations, in particular
                // the proper split between servlet path and path info is not done.
                final ExternalContext externalContext = NetUtils.getExternalContext();
                final ExternalContext.Request request = externalContext.getRequest();

                // TODO: Temporary. Use XFormsModelSubmission to load instances instead
                if (!NetUtils.urlHasProtocol(instanceResource) && request.getContainerType().equals("portlet"))
                    throw new UnsupportedOperationException("<xforms:instance src=\"\"> with relative path within a portlet");

                // Use full resolved resource URL
                // o absolute URL, e.g. http://example.org/instance.xml
                // o absolute path relative to server root, e.g. /orbeon/foo/bar/instance.xml
                loadInstance(externalContext, instance);
            }
        } catch (Exception e) {
            final ValidationException validationException
                = ValidationException.wrapException(e, new ExtendedLocationData(instance.locationData(), "reading external instance", instance.element()));
            container.dispatchEvent(new XFormsLinkExceptionEvent(containingDocument, XFormsModel.this, instanceResource, instance.element(), validationException));
        }
    }

    private final InstanceLoader INSTANCE_LOADER = new InstanceLoader();

    // TODO: Use XFormsModelSubmission instead of duplicating code here
    private class InstanceLoader implements XFormsServerSharedInstancesCache.Loader {
        public XFormsInstance load(String instanceStaticId, String modelEffectiveId,
                                           String instanceSourceURI, boolean handleXInclude, long timeToLive, String validation) {
            final URL sourceURL;
            try {
                sourceURL = URLFactory.createURL(instanceSourceURI);
            } catch (MalformedURLException e) {
                throw new OXFException(e);
            }

            if (indentedLogger.isDebugEnabled())
                indentedLogger.logDebug("load", "loading instance into cache", "id", instanceStaticId, "URI", instanceSourceURI);

            final ExternalContext externalContext = NetUtils.getExternalContext();
            final ConnectionResult connectionResult = new Connection().open(externalContext,
                    indentedLogger, BaseSubmission.isLogBody(), Connection.Method.GET.name(), sourceURL, null, null, null, null,
                    XFormsProperties.getForwardSubmissionHeaders(containingDocument));

            // Handle connection errors
            if (connectionResult.statusCode != 200) {
                connectionResult.close();
                throw new OXFException("Got invalid return code while loading instance from URI: " + instanceSourceURI + ", " + connectionResult.statusCode);
            }

            try {
                // Read result as XML and create new shared instance
                // TODO: Handle validating?
                final DocumentInfo documentInfo = TransformerUtils.readTinyTree(containingDocument.getStaticState().xpathConfiguration(),
                        connectionResult.getResponseInputStream(), connectionResult.resourceURI, handleXInclude, true);
                return new XFormsInstance(instanceStaticId, effectiveId, instanceSourceURI,
                        null, true, timeToLive, null, true, validation, handleXInclude,
                        XFormsProperties.isExposeXPathTypes(containingDocument), documentInfo, false);
            } catch (Exception e) {
                throw new OXFException("Got exception while loading instance from URI: " + instanceSourceURI, e);
            } finally {
                // Clean-up
                connectionResult.close();
            }
        }
    }

    /*
     * Load an external instance using an absolute URL.
     */
    private void loadInstance(ExternalContext externalContext, Instance instance) {

        final String absoluteURLString = XFormsUtils.resolveServiceURL(containingDocument, instance.element(), instance.instanceSource(),
                ExternalContext.Response.REWRITE_MODE_ABSOLUTE);

        assert NetUtils.urlHasProtocol(absoluteURLString);

        // Connect using external protocol

        final Object instanceDocument;// Document or DocumentInfo
        if (containingDocument.getURIResolver() == null) {
            // Connect directly if there is no resolver or if the instance is globally cached
            // NOTE: If there is no resolver, URLs of the form input:* are not allowed

            assert ProcessorImpl.getProcessorInputSchemeInputName(absoluteURLString) == null;

            if (indentedLogger.isDebugEnabled())
                indentedLogger.logDebug("load", "getting document from URI", "URI", absoluteURLString);

            final URL absoluteResolvedURL;
            try {
                absoluteResolvedURL = URLFactory.createURL(absoluteURLString);
            } catch (MalformedURLException e) {
                throw new OXFException("Invalid URL: " + absoluteURLString);
            }

            final ConnectionResult connectionResult = new Connection().open(externalContext, indentedLogger, BaseSubmission.isLogBody(),
                    Connection.Method.GET.name(), absoluteResolvedURL, instance.credentials(),
                    null, null, null, XFormsProperties.getForwardSubmissionHeaders(containingDocument));

            try {
                // Handle connection errors
                if (connectionResult.statusCode != 200) {
                    throw new OXFException("Got invalid return code while loading instance: " + absoluteURLString + ", " + connectionResult.statusCode);
                }

                // TODO: Handle validating and XInclude!

                // Read result as XML
                // TODO: use submission code
                if (!instance.isReadonlyHint()) {
                    instanceDocument = TransformerUtils.readDom4j(connectionResult.getResponseInputStream(), connectionResult.resourceURI, false, true);
                } else {
                    instanceDocument = TransformerUtils.readTinyTree(containingDocument.getStaticState().xpathConfiguration(),
                            connectionResult.getResponseInputStream(), connectionResult.resourceURI, false, true);
                }
            } finally {
                // Clean-up
                connectionResult.close();
            }

        } else {
            // Optimized case that uses the provided resolver
            if (indentedLogger.isDebugEnabled())
                indentedLogger.logDebug("load", "getting document from resolver", "URI", absoluteURLString);

            // TODO: Handle validating and handleXInclude!

            if (!instance.isReadonlyHint()) {
                instanceDocument = containingDocument.getURIResolver().readAsDom4j(
                        absoluteURLString, instance.credentials(),
                        XFormsProperties.getForwardSubmissionHeaders(containingDocument));
            } else {
                instanceDocument = containingDocument.getURIResolver().readAsTinyTree(containingDocument.getStaticState().xpathConfiguration(),
                        absoluteURLString, instance.credentials(),
                        XFormsProperties.getForwardSubmissionHeaders(containingDocument));
            }
        }

        // Set instance and associated information if everything went well
        // NOTE: No XInclude supported to read instances with @src for now
        setInstanceDocument(instanceDocument, effectiveId, instance.staticId(), absoluteURLString, instance.credentials(), false, -1, instance.xxformsValidation(), false);
    }

    public void performTargetAction(XBLContainer container, XFormsEvent event) {
        // NOP
    }

    private boolean hasInstancesAndBinds() {
        return ! instances.isEmpty() && binds != null;
    }

    public void doRebuild() {

        // Rebuild bind tree only if needed
        if (deferredActionContext.rebuild) {
            // Re-evaluate top-level variables if needed
            resetAndEvaluateVariables();

            if (hasInstancesAndBinds()) {
                // NOTE: contextStack.resetBindingContext(this) called in evaluateVariables()
                binds.rebuild();

                // Controls may have @bind or xxforms:bind() references, so we need to mark them as dirty. Will need dependencies for controls to fix this.
                // TODO: Handle XPathDependencies
                getXBLContainer().requireRefresh();
            }

            // "Actions that directly invoke rebuild, recalculate, revalidate, or refresh always
            // have an immediate effect, and clear the corresponding flag."
            deferredActionContext.rebuild = false;
        }

        // Notify dependencies
        containingDocument.getXPathDependencies().rebuildDone(staticModel);
    }

    public void doRecalculate(boolean applyInitialValues) {

        // Recalculate only if needed
        if (deferredActionContext.recalculate) {

            final boolean hasVariables = ! staticModel.jVariablesSeq().isEmpty();

            // Re-evaluate top-level variables if needed
            if (hasInstancesAndBinds() || hasVariables)
                resetAndEvaluateVariables();

            if (hasInstancesAndBinds()) {
                // Apply calculate binds
                binds.applyCalculateBinds(applyInitialValues);
            }

            // "Actions that directly invoke rebuild, recalculate, revalidate, or refresh always
            // have an immediate effect, and clear the corresponding flag."
            deferredActionContext.recalculate = false;
        }

        // Notify dependencies
        containingDocument.getXPathDependencies().recalculateDone(staticModel);
    }


    public void doRevalidate() {

        // Validate only if needed, including checking the flags, because if validation state is clean, validation
        // being idempotent, revalidating is not needed.
        if (deferredActionContext.revalidate) {
            final boolean mustRevalidate = ! instances.isEmpty() && (mustBindValidate || hasSchema);
            if (mustRevalidate) {
                if (indentedLogger.isDebugEnabled())
                    indentedLogger.startHandleOperation("validation", "performing revalidate", "model id", getEffectiveId());

                // Clear schema validation state
                // NOTE: This could possibly be moved to rebuild(), but we must be careful about the presence of a schema
                for (final XFormsInstance instance: instances) {
                    // Only clear instances that are impacted by xf:bind/(@ref|@nodeset), assuming we were able to figure out the dependencies
                    // The reason is that clearing this state can take quite some time
                    final boolean instanceMightBeSchemaValidated = hasSchema && instance.isSchemaValidation();
                    if (instanceMightBeSchemaValidated) {
                        DataModel.visitElementJava(instance.getInstanceRootElementInfo(), new DataModel.NodeVisitor() {
                            public void visit(NodeInfo nodeInfo) {
                                InstanceData.clearSchemaState(nodeInfo);
                            }
                        });
                    }
                }

                // Run validation
                final Set<String> invalidInstances = new LinkedHashSet<String>();

                // Validate using schemas if needed
                if (hasSchema) {
                    // Apply schemas to all instances
                    for (final XFormsInstance instance : instances) {
                        // Currently we don't support validating read-only instances
                        if (instance.isSchemaValidation()) {
                            if (!schemaValidator.validateInstance(instance)) {
                                // Remember that instance is invalid
                                invalidInstances.add(instance.getEffectiveId());
                            }
                        }
                    }
                }

                // Validate using binds if needed
                if (mustBindValidate)
                    binds.applyValidationBinds(invalidInstances);

                // NOTE: It is possible, with binds and the use of xxforms:instance(), that some instances in
                // invalidInstances do not belong to this model. Those instances won't get events with the dispatching
                // algorithm below.
                // TODO: Must dispatch validity changes, not validity status
                // TODO: Must dispatch after marking revalidate = false, right?
                for (final XFormsInstance instance: instances) {
                    if (invalidInstances.contains(instance.getEffectiveId())) {
                        container.dispatchEvent(new XXFormsInvalidEvent(containingDocument, instance));
                    } else {
                        container.dispatchEvent(new XXFormsValidEvent(containingDocument, instance));
                    }
                }

                if (indentedLogger.isDebugEnabled())
                    indentedLogger.endHandleOperation();
            }

            // "Actions that directly invoke rebuild, recalculate, revalidate, or refresh always
            // have an immediate effect, and clear the corresponding flag."
            deferredActionContext.revalidate = false;
        }

        // Notify dependencies
        containingDocument.getXPathDependencies().revalidateDone(staticModel);
    }

    private void doRefresh() {
        // This is called in response to dispatching xforms-refresh to this model, whether using the xforms:refresh
        // action or by dispatching the event by hand.

        // NOTE: If the refresh flag is not set, we do not call synchronizeAndRefresh() because that would only have the
        // side effect of performing RRR on models, but  but not update the UI, which wouldn't make sense for xforms-refresh.
        // This said, is unlikely (impossible?) that the RRR flags would be set but not the refresh flag.
        if (containingDocument.getControls().isRequireRefresh()) {
            getXBLContainer().synchronizeAndRefresh();
        }
    }

    private final DeferredActionContext deferredActionContext = new DeferredActionContext();

    public class DeferredActionContext {
        public boolean rebuild;
        public boolean recalculate;
        public boolean revalidate;

        public void markStructuralChange() {

            // "XForms Actions that change the tree structure of instance data result in setting all four deferred update
            // flags to true for the model over which they operate"

            rebuild = true;
            recalculate = true;
            revalidate = true;

            getXBLContainer().requireRefresh();
        }

        public void markValueChange(boolean isCalculate) {

            // "XForms Actions that change only the value of an instance node results in setting the flags for
            // recalculate, revalidate, and refresh to true and making no change to the flag for rebuild".

            if (!isCalculate) {
                // Only set recalculate when we are not currently performing a recalculate (avoid infinite loop)
                recalculate = true;
            }

            revalidate = true;

            getXBLContainer().requireRefresh();
        }
    }

    public DeferredActionContext getDeferredActionContext() {
        return deferredActionContext;
    }

    public void markValueChange(NodeInfo nodeInfo, boolean isCalculate) {
        // Set the flags
        deferredActionContext.markValueChange(isCalculate);

        // Notify dependencies of the change
        if (nodeInfo != null)
            containingDocument.getXPathDependencies().markValueChanged(this, nodeInfo);
    }

//    public void markMipChange(NodeInfo nodeInfo) {
//        // Notify dependencies of the change
//        if (nodeInfo != null)
//            containingDocument.getXPathDependencies().markMipChanged(this, nodeInfo);
//    }

    public void markStructuralChange(XFormsInstance instance) {
        // Set the flags
        deferredActionContext.markStructuralChange();

        // Notify dependencies of the change
        containingDocument.getXPathDependencies().markStructuralChange(this, instance);
    }

    public void startOutermostActionHandler() {
        // NOP now that deferredActionContext is always created
    }

    public boolean needRebuildRecalculateRevalidate() {
        return deferredActionContext.rebuild || deferredActionContext.recalculate || deferredActionContext.revalidate;
    }

    public void rebuildRecalculateRevalidateIfNeeded() {
        // Process deferred behavior
        final DeferredActionContext currentDeferredActionContext = deferredActionContext;
        // NOTE: We used to clear deferredActionContext , but this caused events to be dispatched in a different
        // order. So we are now leaving the flag as is, and waiting until they clear themselves.

        if (currentDeferredActionContext.rebuild) {
            containingDocument.startOutermostActionHandler();
            container.dispatchEvent(new XFormsRebuildEvent(containingDocument, this));
            containingDocument.endOutermostActionHandler();
        }
        if (currentDeferredActionContext.recalculate) {
            containingDocument.startOutermostActionHandler();
            container.dispatchEvent(new XFormsRecalculateEvent(containingDocument, this));
            containingDocument.endOutermostActionHandler();
        }
        if (currentDeferredActionContext.revalidate) {
            containingDocument.startOutermostActionHandler();
            container.dispatchEvent(new XFormsRevalidateEvent(containingDocument, this));
            containingDocument.endOutermostActionHandler();
        }
    }

    public XFormsEventObserver getParentEventObserver(XBLContainer container) {
        // There is no point for events to propagate beyond the model
        // NOTE: This could change in the future once models are more integrated in the components hierarchy
        return null;
    }

    public BindingContext getDefaultEvaluationContext() {
        return defaultEvaluationContext;
    }

    public Map<String, ValueRepresentation> getTopLevelVariables() {
        return topLevelVariables;
    }

    // Don't allow any external events
    public boolean allowExternalEvent(String eventName) {
        return false;
    }

    public void addListener(String eventName, EventListener listener) {
        throw new UnsupportedOperationException();
    }

    public void removeListener(String eventName, EventListener listener) {
        throw new UnsupportedOperationException();
    }

    public List<EventListener> getListeners(String eventName) {
        return null;
    }
}
