/*
 * Copyright 2016 Red Hat, Inc.
 * <p>
 * Red Hat licenses this file to you under the Apache License, version
 * 2.0 (the "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 *
 */
package io.syndesis.integration.runtime;

import java.io.InputStream;
import java.util.List;
import java.util.ServiceLoader;

import io.syndesis.integration.model.Flow;
import io.syndesis.integration.model.YamlHelpers;
import io.syndesis.integration.model.SyndesisModel;
import io.syndesis.integration.model.steps.Endpoint;
import io.syndesis.integration.model.steps.Step;
import io.syndesis.integration.runtime.designer.SingleMessageRoutePolicyFactory;
import io.syndesis.integration.support.Strings;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.ProcessorDefinition;
import org.apache.camel.model.RouteDefinition;
import org.apache.camel.util.ResourceHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Camel {@link RouteBuilder} which maps the SyndesisModel rules to Camel routes
 */
public class SyndesisRouteBuilder extends RouteBuilder {
    private static final transient Logger LOG = LoggerFactory.getLogger(SyndesisRouteBuilder.class);

    private final String configurationUri;

    public SyndesisRouteBuilder(String configurationUri) {
        this.configurationUri = configurationUri;
    }

    protected SyndesisModel loadModel() throws Exception {
        try (InputStream is = ResourceHelper.resolveMandatoryResourceAsInputStream(getContext(), configurationUri)) {
            return YamlHelpers.load(is);
        }
    }

    @Override
    public void configure() throws Exception {
        int flowIndex = 0;

        for (Flow flow : loadModel().getFlows()) {
            getContext().setStreamCaching(true);

            if (flow.isTraceEnabled()) {
                getContext().setTracing(true);
            }

            String name = flow.getName();
            if (Strings.isEmpty(name)) {
                name = "flow" + (++flowIndex);
                flow.setName(name);
            }

            List<Step> steps = flow.getSteps();

            if (steps == null || steps.isEmpty()) {
                throw new IllegalStateException("No valid steps! Invalid flow " + flow);
            }

            RouteDefinition route = null;

            for (int i = 0; i < steps.size(); i++) {
                Step step = steps.get(i);

                if (i == 0 && !Endpoint.class.isInstance(step)) {
                    throw new IllegalStateException("No endpoint found as first step (found: " + step.getKind() + ")");
                } else if (i == 0 && Endpoint.class.isInstance(step)) {
                    route = from(Endpoint.class.cast(step).getUri());
                    route.id(name);
                } else {
                    addStep(route, step);
                }

            }

            if (flow.isLogResultEnabled()) {
                route.to("log:" + name + "?showStreams=true");
            }

            if (flow.isSingleMessageModeEnabled()) {
                LOG.info("Enabling single message mode so that only one message is consumed for Design Mode");
                getContext().addRoutePolicyFactory(new SingleMessageRoutePolicyFactory());
            }
        }
    }

    public ProcessorDefinition addSteps(ProcessorDefinition route, Iterable<Step> steps) {
        if (route != null && steps != null) {
            for (Step item : steps) {
                route = addStep(route, item);
            }
        }
        return route;
    }

    public ProcessorDefinition addStep(ProcessorDefinition route, Step item) {
        if (route == null) {
            throw new IllegalArgumentException("You cannot use a " + item.getKind() + " step before you have started a flow with an endpoint or function!");
        }

        for (StepHandler handler : ServiceLoader.load(StepHandler.class, getClass().getClassLoader())) {
            if (handler.canHandle(item)) {
                return handler.handle(item, route, this);
            }
        }

        throw new IllegalStateException("Unknown step kind: " + item + " of class: " + item.getClass().getName());
    }
}
