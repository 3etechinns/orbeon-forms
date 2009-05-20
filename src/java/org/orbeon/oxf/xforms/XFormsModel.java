/**
 *  Copyright (C) 2004 - 2008 Orbeon, Inc.
 *
 *  This program is free software; you can redistribute it and/or modify it under the terms of the
 *  GNU Lesser General Public License as published by the Free Software Foundation; either version
 *  2.1 of the License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 *  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *  See the GNU Lesser General Public License for more details.
 *
 *  The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.xforms;

import org.dom4j.Document;
import org.dom4j.Element;
import org.dom4j.QName;
import org.orbeon.oxf.common.OXFException;
import org.orbeon.oxf.common.ValidationException;
import org.orbeon.oxf.pipeline.api.ExternalContext;
import org.orbeon.oxf.pipeline.api.PipelineContext;
import org.orbeon.oxf.processor.ProcessorImpl;
import org.orbeon.oxf.util.ConnectionResult;
import org.orbeon.oxf.util.NetUtils;
import org.orbeon.oxf.xforms.control.XFormsControl;
import org.orbeon.oxf.xforms.control.XFormsSingleNodeControl;
import org.orbeon.oxf.xforms.control.controls.XFormsRepeatIterationControl;
import org.orbeon.oxf.xforms.event.*;
import org.orbeon.oxf.xforms.event.events.*;
import org.orbeon.oxf.xforms.function.xxforms.XXFormsExtractDocument;
import org.orbeon.oxf.xforms.processor.XFormsServer;
import org.orbeon.oxf.xml.TransformerUtils;
import org.orbeon.oxf.xml.dom4j.ExtendedLocationData;
import org.orbeon.oxf.xml.dom4j.LocationData;
import org.orbeon.saxon.dom4j.NodeWrapper;
import org.orbeon.saxon.om.DocumentInfo;
import org.orbeon.saxon.om.NodeInfo;

import java.net.URI;
import java.net.URL;
import java.util.*;

/**
 * Represents an XForms model.
 */
public class XFormsModel implements XFormsEventTarget, XFormsEventObserver, XFormsObjectResolver {

    private Document modelDocument;

    // Model attributes
    private String modelId;
    private String modelEffectiveId;
    private String fullPrefix;

    // Instances
    private List instanceIds;
    private List instances;
    private Map instancesMap;   // Map<String instanceStaticId, XFormsInstance>

    // Submissions
    private Map submissions;    // Map<String submissionStaticId, XFormsModelSubmission>

    // Binds
    private XFormsModelBinds binds;
    private boolean mustBindValidate;

    // Schema validation
    private XFormsModelSchemaValidator schemaValidator;
    private boolean mustSchemaValidate;

    // Container
    private XFormsContainer container;
    private XFormsContextStack contextStack;    // context stack for evaluation, used by binds, submissions, event handlers

    // Containing document
    private XFormsContainingDocument containingDocument;

    public XFormsModel(XFormsContainer container, String effectiveId, Document modelDocument) {
        this.modelDocument = modelDocument;

        // Basic check trying to make sure this is an XForms model
        // TODO: should rather use schema here or when obtaining document passed to this constructor
        final Element modelElement = modelDocument.getRootElement();
        String rootNamespaceURI = modelElement.getNamespaceURI();
        if (!rootNamespaceURI.equals(XFormsConstants.XFORMS_NAMESPACE_URI))
            throw new ValidationException("Root element of XForms model must be in namespace '"
                    + XFormsConstants.XFORMS_NAMESPACE_URI + "'. Found instead: '" + rootNamespaceURI + "'",
                    (LocationData) modelElement.getData());

        modelId = modelElement.attributeValue("id");
        modelEffectiveId = effectiveId;
        fullPrefix = XFormsUtils.getEffectiveIdPrefix(effectiveId);

        // Extract list of instances ids
        {
            List instanceContainers = modelElement.elements(new QName("instance", XFormsConstants.XFORMS_NAMESPACE));
            instanceIds = new ArrayList(instanceContainers.size());
            if (instanceContainers.size() > 0) {
                for (Iterator i = instanceContainers.iterator(); i.hasNext();) {
                    final Element instanceContainer = (Element) i.next();
                    final String instanceId = XFormsInstance.getInstanceStaticId(instanceContainer);
                    instanceIds.add(instanceId);
                }
            }
        }

        // Set container
        this.container = container;
        this.containingDocument = container.getContainingDocument();

        // Get <xforms:submission> elements (may be missing)
        {
            for (Iterator i = modelElement.elements(new QName("submission", XFormsConstants.XFORMS_NAMESPACE)).iterator(); i.hasNext();) {
                final Element submissionElement = (Element) i.next();
                final String submissionId = submissionElement.attributeValue("id");

                if (this.submissions == null)
                    this.submissions = new HashMap();

                this.submissions.put(submissionId, new XFormsModelSubmission(this.container, submissionId, submissionElement, this));
            }
        }

        // Create binds object
        binds = XFormsModelBinds.create(this);
        mustBindValidate = binds != null;

        // Create context stack
        this.contextStack = new XFormsContextStack(this);
    }

    public void updateEffectiveId(String effectiveId) {
        this.modelEffectiveId = effectiveId;
        this.fullPrefix = XFormsUtils.getEffectiveIdPrefix(effectiveId);

        // Update effective ids of all nested instances
        if (instances != null) {
            for (Iterator i = instances.iterator(); i.hasNext();) {
                final XFormsInstance currentInstance = (XFormsInstance) i.next();
                // NOTE: we pass the new model id, not the instance id
                currentInstance.updateModelEffectiveId(effectiveId);
            }
        }
    }

    public XFormsContextStack getContextStack() {
        return contextStack;
    }

    public XFormsContainer getContainer() {
        return container;
    }

    public XFormsContainer getContainer(XFormsContainingDocument containingDocument) {
        return getContainer();
    }

    public XFormsContainingDocument getContainingDocument() {
        return containingDocument;
    }

    public Document getModelDocument() {
        return modelDocument;
    }

    /**
     * Get object with the id specified.
     */
    public Object getObjectByEffectiveId(String effectiveId) {

        // If prefixes or suffixes don't match, object can't be found here
        if (!getContainer().getFullPrefix().equals(XFormsUtils.getEffectiveIdPrefix(effectiveId))
                || !XFormsUtils.getEffectiveIdSuffix(getContainer().getEffectiveId()).equals(XFormsUtils.getEffectiveIdSuffix(effectiveId))) {
            return null;
        }

        // Find by static id
        return resolveObjectById(null, XFormsUtils.getStaticIdFromId(effectiveId));
    }

    /**
     * Resolve an object. This optionally depends on a source, and involves resolving whether the source is within a
     * repeat or a component.
     *
     * @param sourceEffectiveId  effective id of the source, or null
     * @param targetStaticId     static id of the target
     * @return                   object, or null if not found
     */
    public Object resolveObjectById(String sourceEffectiveId, String targetStaticId) {

        if (targetStaticId.indexOf(XFormsConstants.COMPONENT_SEPARATOR) != -1 || targetStaticId.indexOf(XFormsConstants.REPEAT_HIERARCHY_SEPARATOR_1) != -1)
            throw new OXFException("Target id must be static id: " + targetStaticId);

        // Check this id
        if (targetStaticId.equals(getId()))
            return this;

        // Search instances
        if (instancesMap != null) {
            final XFormsInstance instance = (XFormsInstance) instancesMap.get(targetStaticId);
            if (instance != null)
                return instance;
        }

        // Search submissions
        if (submissions != null) {
            final XFormsModelSubmission resultSubmission = (XFormsModelSubmission) submissions.get(targetStaticId);
            if (resultSubmission != null)
                return resultSubmission;
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
        return (XFormsInstance) ((instances != null && instances.size() > 0) ? instances.get(0) : null);
    }

    /**
     * Return the id of the default instance for this model. Return null if there is no instance in this model.
     *
     * @return  instance id or null
     */
    public String getDefaultInstanceId() {
        return (instanceIds != null && instanceIds.size() > 0) ? (String) instanceIds.get(0) : null;
    }

    /**
     * Return all XFormsInstance objects for this model, in the order they appear in the model.
     */
    public List getInstances() {
        return instances;
    }

    /**
     * Return the number of instances in this model.
     */
    public int getInstanceCount() {
        return instanceIds.size();
    }

    /**
     * Return the XFormsInstance with given id, null if not found.
     */
    public XFormsInstance getInstance(String instanceStaticId) {
        return (instancesMap == null) ? null : (XFormsInstance) (instancesMap.get(instanceStaticId));
    }

    /**
     * Return the XFormsInstance object containing the given node.
     */
    public XFormsInstance getInstanceForNode(NodeInfo nodeInfo) {

        final DocumentInfo documentInfo = nodeInfo.getDocumentRoot();

        if (instances != null) {
            for (Iterator i = instances.iterator(); i.hasNext();) {
                final XFormsInstance currentInstance = (XFormsInstance) i.next();
                if (currentInstance.getDocumentInfo().isSameNodeInfo(documentInfo))
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
                                              String username, String password, boolean shared, long timeToLive, String validation, boolean handleXInclude) {
        // Initialize containers if needed
        if (instances == null) {
            instances = Arrays.asList(new XFormsInstance[instanceIds.size()]);
            instancesMap = new HashMap(instanceIds.size());
        }
        // Prepare and set instance
        final int instancePosition = instanceIds.indexOf(instanceStaticId);
        final XFormsInstance newInstance;
        {
            if (instanceDocument instanceof Document)
                newInstance = new XFormsInstance(modelEffectiveId, instanceStaticId, (Document) instanceDocument, instanceSourceURI, username, password, shared, timeToLive, validation, handleXInclude);
            else if (instanceDocument instanceof DocumentInfo)
                newInstance = new SharedXFormsInstance(modelEffectiveId, instanceStaticId, (DocumentInfo) instanceDocument, instanceSourceURI, username, password, shared, timeToLive, validation, handleXInclude);
            else
                throw new OXFException("Invalid type for instance document: " + instanceDocument.getClass().getName());
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

        // Initialize containers if needed
        if (instances == null) {
            instances = Arrays.asList(new XFormsInstance[instanceIds.size()]);
            instancesMap = new HashMap(instanceIds.size());
        }
        // Prepare and set instance
        final String instanceId = instance.getId();// use static id as instanceIds contains static ids
        final int instancePosition = instanceIds.indexOf(instanceId);

        instances.set(instancePosition, instance);

        // Create mapping instance id -> instance
        instancesMap.put(instanceId, instance);
    }

    public String getId() {
        return modelId;
    }

    public String getEffectiveId() {
        return modelEffectiveId;
    }

    public String getFullPrefix() {
        return fullPrefix;
    }

    public LocationData getLocationData() {
        return (LocationData) modelDocument.getRootElement().getData();
    }

    public XFormsModelBinds getBinds() {
        return binds;
    }

    public XFormsModelSchemaValidator getSchemaValidator() {
        return schemaValidator;
    }

    private void loadSchemasIfNeeded(PipelineContext pipelineContext) {
        if (schemaValidator == null) {
            if (!XFormsProperties.isSkipSchemaValidation(containingDocument)) {
                final Element modelElement = modelDocument.getRootElement();
                schemaValidator = new XFormsModelSchemaValidator(modelElement);
                schemaValidator.loadSchemas(pipelineContext);

                mustSchemaValidate = schemaValidator.hasSchema();
            }
        }
    }

    public String[] getSchemaURIs() {
        if (schemaValidator != null) {
            return schemaValidator.getSchemaURIs();
        } else {
            return null;
        }
    }

    /**
     * Restore the state of the model when the model object was just recreated.
     */
    public void restoreState(PipelineContext pipelineContext ) {
        // Ensure schemas are loaded
        loadSchemasIfNeeded(pipelineContext);

        // Restore instances
        restoreInstances(pipelineContext);

        // Refresh binds
        doRebuild(pipelineContext);
        if (binds != null)
            binds.applyComputedExpressionBinds(pipelineContext);
        doRevalidate(pipelineContext);

        synchronizeInstanceDataEventState();
    }

    /**
     * Restore all the instances serialized as children of the given container element.
     *
     * @param pipelineContext   current PipelineContext containing attribute with serialized instances
     */
    private void restoreInstances(PipelineContext pipelineContext) {

        // Find serialized instances from context
        final Element instancesElement = (Element) pipelineContext.getAttribute(XFormsContainingDocument.XFORMS_DYNAMIC_STATE_RESTORE_INSTANCES);

        // Get instances from dynamic state first
        if (instancesElement != null) {
            for (Iterator i = instancesElement.elements().iterator(); i.hasNext();) {
                final Element currentInstanceElement = (Element) i.next();

                // Check that the instance belongs to this model
                final String currentModelEffectiveId = currentInstanceElement.attributeValue("model-id");
                if (modelEffectiveId.equals(currentModelEffectiveId)) {

                    // Create and set instance document on current model
                    final XFormsInstance newInstance = new XFormsInstance(currentInstanceElement);

                    if (newInstance.getDocumentInfo() == null) {
                        // Instance is not initialized yet

                        // This means that the instance was application shared
                        if (!newInstance.isApplicationShared())
                            throw new ValidationException("Non-initialized instance has to be application shared for id: " + newInstance.getEffectiveId(), getLocationData());

                        final SharedXFormsInstance sharedInstance
                                = XFormsServerSharedInstancesCache.instance().find(pipelineContext, containingDocument, newInstance.getId(), newInstance.getEffectiveModelId(),
                                    newInstance.getSourceURI(), newInstance.getTimeToLive(), newInstance.getValidation(), newInstance.isHandleXInclude());

                        setInstance(sharedInstance, false);
                    } else {
                        // Instance is initialized, just use it
                        setInstance(newInstance, newInstance.isReplaced());
                    }

                    containingDocument.logDebug("restore", "restoring instance from dynamic state", new String[] { "model effective id", modelEffectiveId, "instance static id", newInstance.getId() });
                }
            }
        }

        // Then get instances from static state if necessary
        final Map staticInstancesMap = containingDocument.getStaticState().getSharedInstancesMap();
        if (staticInstancesMap != null && staticInstancesMap.size() > 0) {
            for (Iterator instancesIterator = staticInstancesMap.values().iterator(); instancesIterator.hasNext();) {
                final XFormsInstance currentInstance = (XFormsInstance) instancesIterator.next();

                // Check that the instance belongs to this model
                if (modelEffectiveId.equals(currentInstance.getEffectiveModelId())) {
                    if (getInstance(currentInstance.getId()) == null) {
                        // Instance was not set from dynamic state

                        if (currentInstance.getDocumentInfo() == null) {
                            // Instance is not initialized yet

                            // This means that the instance was application shared
                            if (!currentInstance.isApplicationShared())
                                throw new ValidationException("Non-initialized instance has to be application shared for id: " + currentInstance.getEffectiveId(), getLocationData());

                            final SharedXFormsInstance sharedInstance
                                    = XFormsServerSharedInstancesCache.instance().find(pipelineContext, containingDocument, currentInstance.getId(),
                                        currentInstance.getEffectiveModelId(), currentInstance.getSourceURI(), currentInstance.getTimeToLive(), currentInstance.getValidation(), currentInstance.isHandleXInclude());
                            setInstance(sharedInstance, false);
                        } else {
                            // Instance is initialized, just use it
                            setInstance(currentInstance, false);
                        }

                        containingDocument.logDebug("restore", "restoring instance from static state", new String[] { "model effective id", modelEffectiveId, "instance static id", currentInstance.getId() });
                    }
                }
            }
        }
    }

    public void performDefaultAction(final PipelineContext pipelineContext, XFormsEvent event) {
        final String eventName = event.getEventName();
        if (XFormsEvents.XFORMS_MODEL_CONSTRUCT.equals(eventName)) {
            // 4.2.1 The xforms-model-construct Event
            // Bubbles: Yes / Cancelable: No / Context Info: None

            final Element modelElement = modelDocument.getRootElement();

            // 1. All XML Schemas loaded (throws xforms-link-exception)

            loadSchemasIfNeeded(pipelineContext);
            // TODO: throw exception event

            // 2. Create XPath data model from instance (inline or external) (throws xforms-link-exception)
            //    Instance may not be specified.

//            if (instances == null) {
            if (instances == null) {
                instances = Arrays.asList(new XFormsInstance[instanceIds.size()]);
                instancesMap = new HashMap(instanceIds.size());
            }
            {
                // Build initial instance documents
                final List instanceContainers = modelElement.elements(new QName("instance", XFormsConstants.XFORMS_NAMESPACE));
                final XFormsStaticState staticState = containingDocument.getStaticState();
                final Map staticStateInstancesMap = staticState.isInitialized() ? staticState.getSharedInstancesMap() : null;
                if (instanceContainers.size() > 0) {
                    // Iterate through all instances
                    int instancePosition = 0;
                    for (Iterator i = instanceContainers.iterator(); i.hasNext(); instancePosition++) {

                        final Element instanceContainerElement = (Element) i.next();
                        final LocationData locationData = (LocationData) instanceContainerElement.getData();
                        final String instanceStaticId = XFormsInstance.getInstanceStaticId(instanceContainerElement);

                        // Handle read-only hints
                        final boolean isReadonlyHint = XFormsInstance.isReadonlyHint(instanceContainerElement);
                        final boolean isApplicationSharedHint = XFormsInstance.isApplicationSharedHint(instanceContainerElement);
                        final long xxformsTimeToLive = XFormsInstance.getTimeToLive(instanceContainerElement);

                        // Skip processing in case somebody has already set this particular instance
                        if (instances.get(instancePosition) != null) {
                            continue;
                        }

                        // Get instance from static state if possible
                        if (staticStateInstancesMap != null) {
                            final XFormsInstance staticStateInstance = (XFormsInstance) staticStateInstancesMap.get(instanceStaticId);
                            if (staticStateInstance != null) {
                                // The instance is already available in the static state

                                if (staticStateInstance.getDocumentInfo() == null) {
                                    // Instance is not initialized yet

                                    // This means that the instance was application shared
                                    if (!staticStateInstance.isApplicationShared())
                                        throw new ValidationException("Non-initialized instance has to be application shared for id: " + staticStateInstance.getEffectiveId(),
                                                (LocationData) instanceContainerElement.getData());

                                    if (XFormsServer.logger.isDebugEnabled())
                                        containingDocument.logDebug("model", "using instance from application shared instance cache (instance from static state was not initialized)",
                                                new String[] { "id", staticStateInstance.getEffectiveId() });

                                    final SharedXFormsInstance sharedInstance
                                            = XFormsServerSharedInstancesCache.instance().find(pipelineContext, containingDocument, staticStateInstance.getId(),
                                                staticStateInstance.getEffectiveModelId(), staticStateInstance.getSourceURI(), staticStateInstance.getTimeToLive(),
                                                staticStateInstance.getValidation(), staticStateInstance.isHandleXInclude());
                                    setInstance(sharedInstance, false);

                                } else {
                                    // Instance is initialized, just use it

                                    if (XFormsServer.logger.isDebugEnabled())
                                        containingDocument.logDebug("model", "using initialized instance from static state",
                                                new String[] { "id", staticStateInstance.getEffectiveId() });

                                    setInstance(staticStateInstance, false);
                                }

                                continue;
                            }
                        }

                        // Did not get the instance from static state
                        final String xxformsValidation = instanceContainerElement.attributeValue(XFormsConstants.XXFORMS_VALIDATION_QNAME);

                        containingDocument.startHandleOperation("model", "loading instance",
                                new String[] { "instance id", instanceContainerElement.attributeValue("id") });
                        {
                            // Get instance resource URI, can be from @src or @resource
                            //final String instanceResource = getOriginalInstanceResourceURI(instanceContainerElement);

                            final String srcAttribute = instanceContainerElement.attributeValue("src");
                            final String resourceAttribute = instanceContainerElement.attributeValue("resource");
                            final List children = instanceContainerElement.elements();
                            if (srcAttribute != null) {
                                // "If the src attribute is given, then it takes precedence over inline content and the
                                // resource attribute, and the XML data for the instance is obtained from the link."

                                if (srcAttribute.trim().equals("")) {
                                    // Got a blank src attribute, just dispatch xforms-link-exception
                                    final LocationData extendedLocationData = new ExtendedLocationData(locationData, "processing XForms instance", instanceContainerElement);
                                    final Throwable throwable = new ValidationException("Invalid blank URL specified for instance: " + instanceStaticId, extendedLocationData);
                                    container.dispatchEvent(pipelineContext, new XFormsLinkExceptionEvent(XFormsModel.this, srcAttribute, instanceContainerElement, throwable));
                                    break;
                                }

                                // Load instance
                                loadExternalInstance(pipelineContext, instanceContainerElement, instanceStaticId, srcAttribute, locationData, isReadonlyHint, isApplicationSharedHint, xxformsTimeToLive, xxformsValidation);
                            } else if (children != null && children.size() >= 1) {
                                // "If the src attribute is omitted, then the data for the instance is obtained from
                                // inline content if it is given or the resource attribute otherwise. If both the
                                // resource attribute and inline content are provided, the inline content takes
                                // precedence."

                                final String xxformsExcludeResultPrefixes = instanceContainerElement.attributeValue(XFormsConstants.XXFORMS_EXCLUDE_RESULT_PREFIXES);

                                if (children.size() > 1) {
                                    final LocationData extendedLocationData = new ExtendedLocationData(locationData, "processing XForms instance", instanceContainerElement);
                                    final Throwable throwable = new ValidationException("xforms:instance element must contain exactly one child element", extendedLocationData);
                                    container.dispatchEvent(pipelineContext, new XFormsLinkExceptionEvent(XFormsModel.this, null, instanceContainerElement, throwable));
                                    break;
                                }

                                try {
                                    // Extract document
                                    final Object instanceDocument = XXFormsExtractDocument.extractDocument((Element) children.get(0), xxformsExcludeResultPrefixes, isReadonlyHint);

                                    // Set instance and associated information if everything went well
                                    // NOTE: No XInclude supported to read instances with @src for now
                                    setInstanceDocument(instanceDocument, modelEffectiveId, instanceStaticId, null, null, null, false, -1, xxformsValidation, false);
                                } catch (Exception e) {
                                    final LocationData extendedLocationData = new ExtendedLocationData(locationData, "processing XForms instance", instanceContainerElement);
                                    final Throwable throwable = new ValidationException("Error extracting or setting inline instance", extendedLocationData);
                                    container.dispatchEvent(pipelineContext, new XFormsLinkExceptionEvent(XFormsModel.this, null, instanceContainerElement, throwable));
                                    break;
                                }
                            } else if (resourceAttribute != null) {
                                // "the data for the instance is obtained from inline content if it is given or the
                                // resource attribute otherwise"

                                // Load instance
                                loadExternalInstance(pipelineContext, instanceContainerElement, instanceStaticId, resourceAttribute, locationData, isReadonlyHint, isApplicationSharedHint, xxformsTimeToLive, xxformsValidation);
                            } else {
                                // Everything missing
                                final LocationData extendedLocationData = new ExtendedLocationData(locationData, "processing XForms instance", instanceContainerElement);
                                final Throwable throwable = new ValidationException("Required @src attribute, @resource attribute, or inline content for instance: " + instanceStaticId, extendedLocationData);
                                container.dispatchEvent(pipelineContext, new XFormsLinkExceptionEvent(XFormsModel.this, "", instanceContainerElement, throwable));
                                break;
                            }
                        }
                        containingDocument.endHandleOperation();
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
            doRebuild(pipelineContext);
            doRecalculate(pipelineContext);
            doRevalidate(pipelineContext);

            synchronizeInstanceDataEventState();

        } else if (XFormsEvents.XXFORMS_READY.equals(eventName)) {

            // This is called after xforms-ready events have been dispatched to all models

            final XFormsStaticState staticState = containingDocument.getStaticState();

            if (staticState != null && !staticState.isInitialized()) {
                // The static state is open to adding instances
                if (getInstances() != null) {
                    for (Iterator instanceIterator = getInstances().iterator(); instanceIterator.hasNext();) {
                        final XFormsInstance currentInstance = (XFormsInstance) instanceIterator.next();

                        if (currentInstance instanceof SharedXFormsInstance) {

                            // NOTE: We add all shared instances, even the globally shared ones, and the static state
                            // decides of the amount of information to actually store
                            if (XFormsServer.logger.isDebugEnabled())
                                containingDocument.logDebug("model", "adding read-only instance to static state",
                                    new String[] { "instance", currentInstance.getEffectiveId() });
                            staticState.addSharedInstance((SharedXFormsInstance) currentInstance);
                        }
                        // TODO: something like staticState.hasReset(modelId);
                        // TODO: maybe we won't do this here, but by restoring the initial dynamic state instead
//                        final boolean modelHasReset = false;
//                        else if (modelHasReset) {
//                            if (XFormsServer.logger.isDebugEnabled())
//                                containingDocument.logDebug("model", "adding reset instance to static state",
//                                    new String[] { "instance", currentInstance.getEffectiveId() });
//                            staticState.addSharedInstance(currentInstance.createSharedInstance());
//                        }
                    }
                }
            }

        } else if (XFormsEvents.XFORMS_MODEL_CONSTRUCT_DONE.equals(eventName)) {
            // 4.2.2 The xforms-model-construct-done Event
            // Bubbles: Yes / Cancelable: No / Context Info: None

            // TODO: implicit lazy instance construction

        } else if (XFormsEvents.XFORMS_REBUILD.equals(eventName)) {
            // 4.3.7 The xforms-rebuild Event
            // Bubbles: Yes / Cancelable: Yes / Context Info: None

            doRebuild(pipelineContext);

        } else if (XFormsEvents.XFORMS_RECALCULATE.equals(eventName)) {
            // 4.3.6 The xforms-recalculate Event
            // Bubbles: Yes / Cancelable: Yes / Context Info: None

            doRecalculate(pipelineContext);

        } else if (XFormsEvents.XFORMS_REVALIDATE.equals(eventName)) {
            // 4.3.5 The xforms-revalidate Event
            // Bubbles: Yes / Cancelable: Yes / Context Info: None

            doRevalidate(pipelineContext);

        } else if (XFormsEvents.XFORMS_REFRESH.equals(eventName)) {
            // 4.3.4 The xforms-refresh Event
            // Bubbles: Yes / Cancelable: Yes / Context Info: None

            containingDocument.getControls().doRefresh(pipelineContext, this);

        } else if (XFormsEvents.XFORMS_RESET.equals(eventName)) {
            // 4.3.8 The xforms-reset Event
            // Bubbles: Yes / Cancelable: Yes / Context Info: None

            // TODO
            // "The instance data is reset to the tree structure and values it had immediately
            // after having processed the xforms-ready event."

            // "Then, the events xforms-rebuild, xforms-recalculate, xforms-revalidate and
            // xforms-refresh are dispatched to the model element in sequence."
            container.dispatchEvent(pipelineContext, new XFormsRebuildEvent(XFormsModel.this));
            container.dispatchEvent(pipelineContext, new XFormsRecalculateEvent(XFormsModel.this));
            container.dispatchEvent(pipelineContext, new XFormsRevalidateEvent(XFormsModel.this));
            container.dispatchEvent(pipelineContext, new XFormsRefreshEvent(XFormsModel.this));

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
                throw new ValidationException("Received fatal error event: " + eventName, throwable, (LocationData) modelDocument.getRootElement().getData());
        }
    }

    private void loadExternalInstance(PipelineContext pipelineContext, Element instanceContainerElement, String instanceStaticId, String instanceResource, LocationData locationData, boolean readonlyHint, boolean applicationSharedHint, long xxformsTimeToLive, String xxformsValidation) {
        // External instance
        final ExternalContext externalContext = (ExternalContext) pipelineContext.getAttribute(PipelineContext.EXTERNAL_CONTEXT);

        // Perform HHRI encoding
        final String instanceResourceHHRI = XFormsUtils.encodeHRRI(instanceResource, true);

        // Resolve to absolute resource path
        final String resourceAbsolutePathOrAbsoluteURL
                = XFormsUtils.resolveServiceURL(pipelineContext, instanceContainerElement, instanceResourceHHRI,
                    ExternalContext.Response.REWRITE_MODE_ABSOLUTE_PATH);

        try {
            if (applicationSharedHint && ProcessorImpl.getProcessorInputSchemeInputName(resourceAbsolutePathOrAbsoluteURL) != null) {
                // Instance can be shared
                // NOTE: We don't allow sharing for input:* URLs because this is very likely NOT to make sense

                // TODO: This doesn't handle optimized submissions!

                // Make sure URL is absolute to make it unique
                final String absoluteResolvedURLString; {
                    // TODO: most likely WRONG; should use resolveServiceURL(), right?
                    final URL absoluteResolvedURL = NetUtils.createAbsoluteURL(resourceAbsolutePathOrAbsoluteURL, null, externalContext);
                    absoluteResolvedURLString = absoluteResolvedURL.toExternalForm();
                }

                // NOTE: No XInclude supported to read instances with @src for now
                final SharedXFormsInstance sharedXFormsInstance
                        = XFormsServerSharedInstancesCache.instance().find(pipelineContext, containingDocument, instanceStaticId, modelEffectiveId,
                            absoluteResolvedURLString, xxformsTimeToLive, xxformsValidation, false);
                setInstance(sharedXFormsInstance, false);
            } else {
                // Instance cannot be shared

                // NOTE: Optimizing with include() for servlets has limitations, in particular
                // the proper split between servlet path and path info is not done.
                final ExternalContext.Request request = externalContext.getRequest();
                final boolean optimizeLocal =
                        !NetUtils.urlHasProtocol(resourceAbsolutePathOrAbsoluteURL)
                                && (request.getContainerType().equals("portlet")
                                    || request.getContainerType().equals("servlet")
                                       && XFormsProperties.isOptimizeLocalInstanceInclude(containingDocument));

                if (optimizeLocal) {
                    // Use URL resolved against current context
                    final URI resolvedURI = XFormsUtils.resolveXMLBase(instanceContainerElement, instanceResourceHHRI);
                    loadInstanceOptimized(pipelineContext, externalContext, instanceContainerElement, instanceStaticId, resolvedURI.toString(), readonlyHint, xxformsValidation);
                } else {
                    // Use full resolved resource URL
                    // o absolute URL, e.g. http://example.org/instance.xml
                    // o absolute path relative to server root, e.g. /orbeon/foo/bar/instance.xml
                    loadInstance(externalContext, instanceContainerElement, instanceStaticId, resourceAbsolutePathOrAbsoluteURL, readonlyHint, xxformsValidation);
                }
            }
        } catch (Exception e) {
            final ValidationException validationException
                = ValidationException.wrapException(e, new ExtendedLocationData(locationData, "reading external instance", instanceContainerElement));
            container.dispatchEvent(pipelineContext, new XFormsLinkExceptionEvent(XFormsModel.this, resourceAbsolutePathOrAbsoluteURL, instanceContainerElement, validationException));
        }
    }

    private void loadInstance(ExternalContext externalContext, Element instanceContainerElement,
                                          String instanceId, String resourceAbsolutePathOrAbsoluteURL, boolean isReadonlyHint, String xxformsValidation) {

        // Connect using external protocol

        final URL absoluteResolvedURL;
        final String absoluteResolvedURLString;
        {

            final String inputName = ProcessorImpl.getProcessorInputSchemeInputName(resourceAbsolutePathOrAbsoluteURL);
            if (inputName != null) {
                // URL is input:*, keep it as is
                absoluteResolvedURL = null;
                absoluteResolvedURLString = resourceAbsolutePathOrAbsoluteURL;
            } else {
                // URL is regular URL, make sure it is absolute
                absoluteResolvedURL = NetUtils.createAbsoluteURL(resourceAbsolutePathOrAbsoluteURL, null, externalContext);
                absoluteResolvedURLString = absoluteResolvedURL.toExternalForm();
            }
        }

        // Extension: username and password
        // NOTE: Those don't use AVTs for now, because XPath expressions in those could access
        // instances that haven't been loaded yet.
        final String xxformsUsername = instanceContainerElement.attributeValue(XFormsConstants.XXFORMS_USERNAME_QNAME);
        final String xxformsPassword = instanceContainerElement.attributeValue(XFormsConstants.XXFORMS_PASSWORD_QNAME);

        final Object instanceDocument;// Document or DocumentInfo
        if (containingDocument.getURIResolver() == null) {
            // Connect directly if there is no resolver or if the instance is globally shared

            if (XFormsServer.logger.isDebugEnabled())
                containingDocument.logDebug("model", "getting document from URI",
                        new String[] { "URI", absoluteResolvedURLString });

            final ConnectionResult connectionResult = NetUtils.openConnection(externalContext, containingDocument.getIndentedLogger(),
                    "GET", absoluteResolvedURL, xxformsUsername, xxformsPassword, null, null, null,
                    XFormsProperties.getForwardSubmissionHeaders(containingDocument));

            try {
                // Handle connection errors
                if (connectionResult.statusCode != 200) {
                    throw new OXFException("Got invalid return code while loading instance: " + absoluteResolvedURLString + ", " + connectionResult.statusCode);
                }

                // TODO: Handle validating and XInclude!

                // Read result as XML
                if (!isReadonlyHint) {
                    instanceDocument = TransformerUtils.readDom4j(connectionResult.getResponseInputStream(), connectionResult.resourceURI, false);
                } else {
                    instanceDocument = TransformerUtils.readTinyTree(connectionResult.getResponseInputStream(), connectionResult.resourceURI, false);
                }
            } finally {
                // Clean-up
                if (connectionResult != null)
                    connectionResult.close();
            }

        } else {
            // Optimized case that uses the provided resolver
            if (XFormsServer.logger.isDebugEnabled())
                containingDocument.logDebug("model", "getting document from resolver",
                        new String[] { "URI", absoluteResolvedURLString });

            // TODO: Handle validating and handleXInclude!

            if (!isReadonlyHint) {
                instanceDocument = containingDocument.getURIResolver().readURLAsDocument(absoluteResolvedURLString, xxformsUsername, xxformsPassword,
                        XFormsProperties.getForwardSubmissionHeaders(containingDocument));
            } else {
                instanceDocument = containingDocument.getURIResolver().readURLAsDocumentInfo(absoluteResolvedURLString, xxformsUsername, xxformsPassword,
                        XFormsProperties.getForwardSubmissionHeaders(containingDocument));
            }
        }

        // Set instance and associated information if everything went well
        // NOTE: No XInclude supported to read instances with @src for now
        setInstanceDocument(instanceDocument, modelEffectiveId, instanceId, absoluteResolvedURLString, xxformsUsername, xxformsPassword, false, -1, xxformsValidation, false);
    }

    private void loadInstanceOptimized(PipelineContext pipelineContext, ExternalContext externalContext, Element instanceContainerElement,
                                          String instanceId, String resourceAbsolutePathOrAbsoluteURL, boolean isReadonlyHint, String xxformsValidation) {

        // Whether or not to rewrite URLs
        final boolean isNoRewrite = XFormsUtils.resolveUrlNorewrite(instanceContainerElement);

        if (XFormsServer.logger.isDebugEnabled())
            containingDocument.logDebug("model", "getting document from optimized URI",
                        new String[] { "URI", resourceAbsolutePathOrAbsoluteURL, "norewrite", Boolean.toString(isNoRewrite) });

        // Run submission
        final ConnectionResult connectionResult = XFormsSubmissionUtils.openOptimizedConnection(pipelineContext, externalContext, containingDocument,
                null, "get", resourceAbsolutePathOrAbsoluteURL, isNoRewrite, null, null, null, false,
                XFormsSubmissionUtils.MINIMAL_HEADERS_TO_FORWARD, null);

        final Object instanceDocument;// Document or DocumentInfo
        try {
            // Handle connection errors
            if (connectionResult.statusCode != 200) {
                throw new OXFException("Got invalid return code while loading instance: " + resourceAbsolutePathOrAbsoluteURL + ", " + connectionResult.statusCode);
            }

            // TODO: Handle validating and handleXInclude!

            // Read result as XML
            if (!isReadonlyHint) {
                instanceDocument = TransformerUtils.readDom4j(connectionResult.getResponseInputStream(), connectionResult.resourceURI, false);
            } else {
                instanceDocument = TransformerUtils.readTinyTree(connectionResult.getResponseInputStream(), connectionResult.resourceURI, false);
            }
        } finally {
            // Clean-up
            if (connectionResult != null)
                connectionResult.close();
        }

        // Set instance and associated information if everything went well
        // NOTE: No XInclude supported to read instances with @src for now
        setInstanceDocument(instanceDocument, modelEffectiveId, instanceId, resourceAbsolutePathOrAbsoluteURL, null, null, false, -1, xxformsValidation, false);
    }

    public void performTargetAction(PipelineContext pipelineContext, XFormsContainer container, XFormsEvent event) {
        // NOP
    }

    public void synchronizeInstanceDataEventState() {
        if (instances != null) {
            for (Iterator i = instances.iterator(); i.hasNext();) {
                final XFormsInstance currentInstance = (XFormsInstance) i.next();
                currentInstance.synchronizeInstanceDataEventState();
            }
        }
    }

    public void doRebuild(PipelineContext pipelineContext) {

        // Rebuild bind tree
        if (binds != null) {
            binds.rebuild(pipelineContext);
            // TODO: rebuild computational dependency data structures

            // Controls may have @bind or xxforms:bind() references, so we need to mark them as dirty. Will need dependencies for controls to fix this.
            containingDocument.getControls().markDirtySinceLastRequest(true);
        }

        // "Actions that directly invoke rebuild, recalculate, revalidate, or refresh always
        // have an immediate effect, and clear the corresponding flag."
        deferredActionContext.rebuild = false;
    }

    public void doRecalculate(PipelineContext pipelineContext) {

        if (instances != null && binds != null) {
            // Apply calculate binds
            binds.applyCalculateBinds(pipelineContext);
        }

        // "Actions that directly invoke rebuild, recalculate, revalidate, or refresh always
        // have an immediate effect, and clear the corresponding flag."
        deferredActionContext.recalculate = false;
    }


    public void doRevalidate(final PipelineContext pipelineContext) {

        if (instances != null && (mustBindValidate || mustSchemaValidate)) {
            if (XFormsServer.logger.isDebugEnabled())
                containingDocument.startHandleOperation("model", "performing revalidate", new String[] { "model id", getEffectiveId() });

            // Clear validation state
            for (Iterator i = instances.iterator(); i.hasNext();) {
                XFormsUtils.iterateInstanceData(((XFormsInstance) i.next()), new XFormsUtils.InstanceWalker() {
                    public void walk(NodeInfo nodeInfo) {
                        InstanceData.clearValidationState(nodeInfo);
                    }
                }, true);
            }

            // Run validation
            final Map invalidInstances = new HashMap();

            // Validate using schemas if needed
            if (mustSchemaValidate) {
                // Apply schemas to all instances
                for (Iterator i = instances.iterator(); i.hasNext();) {
                    final XFormsInstance currentInstance = (XFormsInstance) i.next();
                    // Currently we don't support validating read-only instances
                    if (!currentInstance.isReadOnly()) {
                        if (!schemaValidator.validateInstance(currentInstance)) {
                            // Remember that instance is invalid
                            invalidInstances.put(currentInstance.getEffectiveId(), "");
                        }
                    }
                }
            }

            // Validate using binds if needed
            if (mustBindValidate)
                binds.applyValidationBinds(pipelineContext, invalidInstances);

            // NOTE: It is possible, with binds and the use of xxforms:instance(), that some instances in
            // invalidInstances do not belong to this model. Those instances won't get events with the dispatching
            // algorithm below.
            for (Iterator i = instances.iterator(); i.hasNext();) {
                final XFormsInstance instance = (XFormsInstance) i.next();
                if (invalidInstances.get(instance.getEffectiveId()) == null) {
                    container.dispatchEvent(pipelineContext, new XXFormsValidEvent(instance));
                } else {
                    container.dispatchEvent(pipelineContext, new XXFormsInvalidEvent(instance));
                }
            }

            if (XFormsServer.logger.isDebugEnabled())
                containingDocument.endHandleOperation();
        }

        // "Actions that directly invoke rebuild, recalculate, revalidate, or refresh always
        // have an immediate effect, and clear the corresponding flag."
        deferredActionContext.revalidate = false;
    }

    /**
     * Handle events related to externally updating one or more instance documents.
     */
    public void handleNewInstanceDocument(PipelineContext pipelineContext, final XFormsInstance newInstance) {

        // Set the instance on this model
        setInstance(newInstance, true);

        // NOTE: The current spec specifies direct calls, but it might be updated to require setting flags instead.
        {
            final XFormsModel.DeferredActionContext deferredActionContext = getDeferredActionContext();
            deferredActionContext.rebuild = true;
            deferredActionContext.recalculate = true;
            deferredActionContext.revalidate = true;
        }

        final XFormsControls xformsControls = containingDocument.getControls();
        if (xformsControls.isInitialized()) {
            // Controls exist, otherwise there is no point in doing anything controls-related

            // The controls will be dirty
            containingDocument.getControls().markDirtySinceLastRequest(true);

            // As of 2009-03-18 decision, XForms 1.1 specifies that deferred event handling flags are set instead of
            // performing RRRR directly
            deferredActionContext.setAllDeferredFlags(true);

            if (newInstance.isReadOnly()) {
                // Read-only instance: replacing does not cause value change events at the moment, so we just set the
                // flags but do not mark values as changed. Anyway, that event logic is broken, see below.

                // NOP for now
            } else {
                // Read-write instance

                // NOTE: Besides setting the flags, for read-write instances, we go through a marking process used for
                // event dispatch. This process requires:
                //
                // o up-to-date control bindings, for 1) relevance handling and 2) type handling
                // o which in turn requires RRR
                //
                // So we do perform RRR below, which clears the flags set above. This should be seen as temporary
                // measure until we do proper (i.e. not like XForms 1.1 specifies!) UI event updates.

                // Update control bindings if needed 
                doRebuild(pipelineContext);
                doRecalculate(pipelineContext);
                doRevalidate(pipelineContext);
                xformsControls.updateControlBindingsIfNeeded(pipelineContext);

                if (XFormsServer.logger.isDebugEnabled())
                    containingDocument.logDebug("model", "marking nodes for value change following instance replacement",
                            new String[] { "instance id", newInstance.getEffectiveId() });

                // Mark all nodes to which single-node controls are bound
                xformsControls.visitAllControls(new XFormsControls.XFormsControlVisitorAdapter() {
                    public void startVisitControl(XFormsControl control) {

                        // Don't do anything if it's not a single node control
                        // NOTE: We don't dispatch events to repeat iterations
                        if (!(control instanceof XFormsSingleNodeControl && !(control instanceof XFormsRepeatIterationControl)))
                            return;

                        // This can happen if control is not bound to anything (includes xforms:group[not(@ref) and not(@bind)])
                        final NodeInfo currentNodeInfo = control.getBoundNode();
                        if (currentNodeInfo == null)
                            return;

                        // We only mark nodes in mutable documents
                        if (!(currentNodeInfo instanceof NodeWrapper))
                            return;

                        // We only mark nodes in the replaced instance
                        if (getInstanceForNode(currentNodeInfo) != newInstance)
                            return;

                        // Finally, mark node
                        InstanceData.markValueChanged(currentNodeInfo);
                    }
                });
            }
        }
    }

    private final DeferredActionContext deferredActionContext = new DeferredActionContext();

    public static class DeferredActionContext {
        public boolean rebuild;
        public boolean recalculate;
        public boolean revalidate;
        public boolean refresh;

        public void setAllDeferredFlags(boolean value) {
            rebuild = value;
            recalculate = value;
            revalidate = value;
            refresh = value;
        }
    }

    public DeferredActionContext getDeferredActionContext() {
        return deferredActionContext;
    }

    public void setAllDeferredFlags(boolean value) {
        deferredActionContext.setAllDeferredFlags(value);
    }

    public void refreshDone() {
        deferredActionContext.refresh = false;
    }

    public void startOutermostActionHandler() {
        // NOP now that deferredActionContext is always created
    }

    public void endOutermostActionHandler(PipelineContext pipelineContext) {

        // Process deferred behavior
        final DeferredActionContext currentDeferredActionContext = deferredActionContext;
        // NOTE: We used to clear deferredActionContext , but this caused events to be dispatched in a different
        // order. So we are now leaving the flag as is, and waiting until they clear themselves.

        if (currentDeferredActionContext.rebuild) {
            containingDocument.startOutermostActionHandler();
            container.dispatchEvent(pipelineContext, new XFormsRebuildEvent(this));
            containingDocument.endOutermostActionHandler(pipelineContext);
        }
        if (currentDeferredActionContext.recalculate) {
            containingDocument.startOutermostActionHandler();
            container.dispatchEvent(pipelineContext, new XFormsRecalculateEvent(this));
            containingDocument.endOutermostActionHandler(pipelineContext);
        }
        if (currentDeferredActionContext.revalidate) {
            containingDocument.startOutermostActionHandler();
            container.dispatchEvent(pipelineContext, new XFormsRevalidateEvent(this));
            containingDocument.endOutermostActionHandler(pipelineContext);
        }
        if (currentDeferredActionContext.refresh) {
            containingDocument.startOutermostActionHandler();
            container.dispatchEvent(pipelineContext, new XFormsRefreshEvent(this));
            containingDocument.endOutermostActionHandler(pipelineContext);
        }
    }

    public void rebuildRecalculateIfNeeded(PipelineContext pipelineContext) {
        if (deferredActionContext.rebuild) {
            doRebuild(pipelineContext);
        }
        if (deferredActionContext.recalculate) {
            doRecalculate(pipelineContext);
        }
    }

    public XFormsEventObserver getParentEventObserver(XFormsContainer container) {
        // There is no point for events to propagate beyond the model
        // TODO: This could change in the future once models are more integrated in the components hierarchy
        return null;
    }

    /**
     * Return the List of XFormsEventHandler objects within this object.
     */
    public List getEventHandlers(XFormsContainer container) {
        return containingDocument.getStaticState().getEventHandlers(XFormsUtils.getEffectiveIdNoSuffix(getEffectiveId()));
    }
}
