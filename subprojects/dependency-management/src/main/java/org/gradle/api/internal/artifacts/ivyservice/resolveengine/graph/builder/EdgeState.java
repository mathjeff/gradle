/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal.artifacts.ivyservice.resolveengine.graph.builder;

import org.gradle.internal.component.external.model.ConfigurationBoundExternalDependencyMetadata;
import org.gradle.internal.UncheckedException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.gradle.api.Transformer;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.component.ComponentSelector;
import org.gradle.api.artifacts.result.ComponentSelectionReason;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.excludes.ModuleExclusion;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.graph.DependencyGraphEdge;
import org.gradle.api.internal.attributes.ImmutableAttributes;
import org.gradle.internal.component.local.model.DslOriginDependencyMetadata;
import org.gradle.internal.component.model.ComponentArtifactMetadata;
import org.gradle.internal.component.model.ComponentResolveMetadata;
import org.gradle.internal.component.model.ConfigurationMetadata;
import org.gradle.internal.component.model.DependencyMetadata;
import org.gradle.internal.component.model.ExcludeMetadata;
import org.gradle.internal.component.model.IvyArtifactName;
import org.gradle.internal.resolve.ModuleVersionResolveException;
import org.gradle.util.CollectionUtils;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Represents the edges in the dependency graph.
 *
 * A dependency can have the following states:
 * 1. Unattached: in this case the state of the dependency is  tied to the state of its associated {@link SelectorState}.
 * 2. Attached: in this case the Edge has been connected to actual nodes in the target component. Only possible if the {@link SelectorState} did not fail to resolve.
 */
public class EdgeState implements DependencyGraphEdge {
    private final DependencyState dependencyState;
    private final DependencyMetadata dependencyMetadata;
    private final NodeState from;
    private final SelectorState selector;
    private final ResolveState resolveState;
    private final ModuleExclusion transitiveExclusions;
    private final List<NodeState> targetNodes = Lists.newLinkedList();
    private final boolean isTransitive;

    private ModuleVersionResolveException targetNodeSelectionFailure;

    EdgeState(NodeState from, DependencyState dependencyState, ModuleExclusion transitiveExclusions, ResolveState resolveState) {
        this.from = from;
        this.dependencyState = dependencyState;
        this.dependencyMetadata = dependencyState.getDependency();
        // The accumulated exclusions that apply to this edge based on the path from the root
        this.transitiveExclusions = transitiveExclusions;
        this.resolveState = resolveState;
        this.selector = resolveState.getSelector(dependencyState, dependencyState.getModuleIdentifier());
        this.isTransitive = from.isTransitive() && dependencyMetadata.isTransitive();
        this.debugCheck();
    }

    public void debugCheck() {
        if (this.toString().contains("jeff-core.aar (project :jeff-core)") && this.contributesArtifacts()) {
          UncheckedException.throwAsUncheckedException(new Exception("EdgeState is in wrong state"));
        }
    }

    @Override
    public String toString() {
        return String.format("EdgeState (%s -> %s)", from.toString(), dependencyMetadata);
    }

    @Override
    public NodeState getFrom() {
        return from;
    }

    public DependencyMetadata getDependencyMetadata() {
        return dependencyMetadata;
    }

    /**
     * Returns the target component, if the edge has been successfully resolved.
     * Returns null if the edge failed to resolve, or has not (yet) been successfully resolved to a target component.
     */
    @Nullable
    ComponentState getTargetComponent() {
        if (!selector.isResolved() || selector.getFailure() != null) {
            return null;
        }
        return getSelectedComponent();
    }

    @Override
    public SelectorState getSelector() {
        return selector;
    }

    public boolean isTransitive() {
        return isTransitive;
    }

    public void attachToTargetConfigurations() {
        System.out.println("Jeff EdgeState " + this + " attaching to target configurations");
        ComponentState targetComponent = getTargetComponent();
        if (targetComponent == null) {
            // The selector failed or the module has been deselected. Do not attach.
            return;
        }
        calculateTargetConfigurations(targetComponent);
        for (NodeState targetConfiguration : targetNodes) {
            targetConfiguration.addIncomingEdge(this);
        }
        if (!targetNodes.isEmpty()) {
            selector.getTargetModule().removeUnattachedDependency(this);
        }
    }

    public void removeFromTargetConfigurations() {
        System.out.println("Jeff EdgeState " + this + " removing from target configurations");
        if (!targetNodes.isEmpty()) {
            for (NodeState targetConfiguration : targetNodes) {
                targetConfiguration.removeIncomingEdge(this);
            }
            targetNodes.clear();
        }
        targetNodeSelectionFailure = null;
    }

    /**
     * Call this method to attach a failure late in the process. This is typically
     * done when a failure is caused by graph validation. In that case we want to
     * perform as much resolution as possible, still have a valid graph, but in the
     * end fail resolution.
     */
    public void failWith(Throwable err) {
        targetNodeSelectionFailure = new ModuleVersionResolveException(dependencyState.getRequested(), err);
    }

    public void restart() {
        if (from.isSelected()) {
            removeFromTargetConfigurations();
            attachToTargetConfigurations();
        }
    }

    public ImmutableAttributes getAttributes() {
        ModuleResolveState module = selector.getTargetModule();
        return module.getMergedSelectorAttributes();
    }

    private void calculateTargetConfigurations(ComponentState targetComponent) {
        targetNodes.clear();
        targetNodeSelectionFailure = null;
        ComponentResolveMetadata targetModuleVersion = targetComponent.getMetadata();
        if (targetModuleVersion == null) {
            // Broken version
            return;
        }

        List<ConfigurationMetadata> targetConfigurations;
        try {
            ImmutableAttributes attributes = resolveState.getRoot().getMetadata().getAttributes();
            attributes = resolveState.getAttributesFactory().concat(attributes, getAttributes());
            targetConfigurations = dependencyMetadata.selectConfigurations(attributes, targetModuleVersion, resolveState.getAttributesSchema());
        } catch (Throwable t) {
            // Failure to select the target variant/configurations from this component, given the dependency attributes/metadata.
            targetNodeSelectionFailure = new ModuleVersionResolveException(dependencyState.getRequested(), t);
            return;
        }
        for (ConfigurationMetadata targetConfiguration : targetConfigurations) {
            NodeState targetNodeState = resolveState.getNode(targetComponent, targetConfiguration);
            ComponentState targetComponentState = targetNodeState.getComponent();

            

            boolean include = true;
            String ourVersion = null;
            if (this.getDependencyMetadata() instanceof ConfigurationBoundExternalDependencyMetadata) {
              ConfigurationBoundExternalDependencyMetadata converted = (ConfigurationBoundExternalDependencyMetadata)(this.getDependencyMetadata());
              ourVersion = converted.getDependencyDescriptor().getSelector().getVersion();
            }

            String theirVersion = targetComponentState.getVersion();
            //String targetComponentVersion = this.dependencyMetadata.getSelector().getVersion();
            //String targetComponentVersion = targetComponent.getVersion();
            //String targetNodeStateVersion = targetNodeState.getComponent().getVersion();

            //boolean wantsPom = (this isinstance ExternalDependencyDescriptor);
            //boolean isPom = (targetNodeState.getComponent()


            System.out.println("Jeff EdgeState sees targetNodeState = " + targetNodeState + " with version (" + theirVersion + ") (EdgeState's version: " + ourVersion + ")");
            //if (false) { //if (targetComponentVersion != null && targetNodeStateVersion != null && !targetComponentVersion.equals(targetNodeStateVersion)) {
            if (ourVersion != null && theirVersion != null && ourVersion != theirVersion) {
              System.out.println("Jeff EdgeState skipping targetNodeState = " + targetNodeState + " with version (" + theirVersion + ") != EdgeState's version: " + ourVersion);
            } else {
              System.out.println("Jeff EdgeState identified targetNodeState = " + targetNodeState + "(" + targetNodeState.getClass() + ") for targetComponent " + targetComponent + "(" + targetComponent.getClass() + ") and targetConfiguration " + targetConfiguration + " and EdgeState metadata " + this.getDependencyMetadata() + " (" +  this.getDependencyMetadata().getClass() + ")");
              this.targetNodes.add(targetNodeState);
            }
        }
    }

    @Override
    public ModuleExclusion getExclusions() {
        List<ExcludeMetadata> excludes = dependencyMetadata.getExcludes();
        if (excludes.isEmpty()) {
            return transitiveExclusions;
        }
        ModuleExclusion edgeExclusions = resolveState.getModuleExclusions().excludeAny(ImmutableList.copyOf(excludes));
        return resolveState.getModuleExclusions().intersect(edgeExclusions, transitiveExclusions);
    }

    @Override
    public boolean contributesArtifacts() {
        return !dependencyMetadata.isPending();
    }

    @Override
    public ComponentSelector getRequested() {
        return AttributeDesugaring.desugarSelector(dependencyState.getRequested(), from.getAttributesFactory());
    }

    @Override
    public ModuleVersionResolveException getFailure() {
        if (targetNodeSelectionFailure != null) {
            return targetNodeSelectionFailure;
        }
        ModuleVersionResolveException selectorFailure = selector.getFailure();
        if (selectorFailure != null) {
            return selectorFailure;
        }
        return getSelectedComponent().getMetadataResolveFailure();
    }

    @Override
    public Long getSelected() {
        return getSelectedComponent().getResultId();
    }

    @Override
    public ComponentSelectionReason getReason() {
        return selector.getSelectionReason();
    }

    private ComponentState getSelectedComponent() {
        return selector.getTargetModule().getSelected();
    }

    @Override
    public Dependency getOriginalDependency() {
        if (dependencyMetadata instanceof DslOriginDependencyMetadata) {
            return ((DslOriginDependencyMetadata) dependencyMetadata).getSource();
        }
        return null;
    }

    @Override
    public List<ComponentArtifactMetadata> getArtifacts(final ConfigurationMetadata targetConfiguration) {
        return CollectionUtils.collect(dependencyMetadata.getArtifacts(), new Transformer<ComponentArtifactMetadata, IvyArtifactName>() {
            @Override
            public ComponentArtifactMetadata transform(IvyArtifactName ivyArtifactName) {
                return targetConfiguration.artifact(ivyArtifactName);
            }
        });
    }
}
