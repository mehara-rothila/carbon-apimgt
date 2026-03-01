/*
 *  Copyright WSO2 Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.wso2.carbon.apimgt.rest.api.publisher.v1.common.template;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.velocity.VelocityContext;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.model.API;
import org.wso2.carbon.apimgt.api.model.APIProduct;
import org.wso2.carbon.apimgt.api.model.APIProductResource;
import org.wso2.carbon.apimgt.api.model.URITemplate;
import org.wso2.carbon.apimgt.impl.APIConstants;
import org.wso2.carbon.apimgt.impl.utils.APIUtil;
import org.wso2.carbon.apimgt.impl.utils.GatewayUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Set the uri templates as the resources
 */
public class ResourceConfigContext extends ConfigContextDecorator {

    private static final Log log = LogFactory.getLog(ResourceConfigContext.class);

    private API api;
    private APIProduct apiProduct;
    private String faultSeqExt;

    public ResourceConfigContext(ConfigContext context, API api) {

        super(context);
        this.api = api;
    }

    public ResourceConfigContext(ConfigContext context, APIProduct apiProduct) {

        super(context);
        this.apiProduct = apiProduct;
    }

    public void validate() throws APIManagementException {

        if (api != null) {
            if (api.getUriTemplates() == null || api.getUriTemplates().isEmpty()) {
                throw new APIManagementException("At least one resource is required");
            }

            this.faultSeqExt = APIUtil.getFaultSequenceName(api);
        }
    }

    public VelocityContext getContext() {

        VelocityContext context = super.getContext();

        if (api != null) {
            context.put("resources", api.getUriTemplates());
            context.put("apiType", api.getType());
            context.put("faultSequence", faultSeqExt != null ? faultSeqExt : api.getFaultSequence());

            Map<String, JSONObject> resourceEndpointConfigs = new HashMap<>();
            for (URITemplate uriTemplate : api.getUriTemplates()) {
                if (uriTemplate.isResourceEndpointConfigExist()) {
                    try {
                        JSONParser parser = new JSONParser();
                        JSONObject epConfig = (JSONObject) parser.parse(
                                uriTemplate.getResourceEndpointConfig());
                        String key = uriTemplate.getUriTemplate() + ":" + uriTemplate.getHTTPVerb();
                        resourceEndpointConfigs.put(key, epConfig);
                    } catch (ParseException e) {
                        log.error("Error parsing resource endpoint config for "
                                + uriTemplate.getUriTemplate(), e);
                    }
                }
            }
            if (!resourceEndpointConfigs.isEmpty()) {
                context.put("resourceEndpointConfigs", resourceEndpointConfigs);
            }

            Map<String, Map<String, EndpointSecurityModel>> resourceEndpointSecurityMap = new HashMap<>();
            for (Map.Entry<String, JSONObject> entry : resourceEndpointConfigs.entrySet()) {
                JSONObject epConfig = entry.getValue();
                JSONObject epSecurity = (JSONObject) epConfig.get(APIConstants.ENDPOINT_SECURITY);
                if (epSecurity == null) {
                    continue;
                }
                Map<String, EndpointSecurityModel> securityModelMap = new HashMap<>();
                securityModelMap.put(APIConstants.ENDPOINT_SECURITY_PRODUCTION, new EndpointSecurityModel());
                securityModelMap.put(APIConstants.ENDPOINT_SECURITY_SANDBOX, new EndpointSecurityModel());

                Object defIdObj = epConfig.get("_definition_id");
                String defId = defIdObj != null ? defIdObj.toString() : null;
                String resPrefix = defId != null ? "resEP_" + defId : null;

                JSONObject prodSec = (JSONObject) epSecurity.get(APIConstants.ENDPOINT_SECURITY_PRODUCTION);
                if (prodSec != null) {
                    EndpointSecurityModel prodModel = new ObjectMapper().convertValue(prodSec,
                            EndpointSecurityModel.class);
                    prodModel = retrieveResourceEndpointSecurityModel(prodModel,
                            api.getId().getApiName(), api.getId().getVersion(), api.getUuid(),
                            APIConstants.ENDPOINT_SECURITY_PRODUCTION, resPrefix);
                    if (prodModel != null) {
                        securityModelMap.put(APIConstants.ENDPOINT_SECURITY_PRODUCTION, prodModel);
                    }
                }

                JSONObject sandSec = (JSONObject) epSecurity.get(APIConstants.ENDPOINT_SECURITY_SANDBOX);
                if (sandSec != null) {
                    EndpointSecurityModel sandModel = new ObjectMapper().convertValue(sandSec,
                            EndpointSecurityModel.class);
                    sandModel = retrieveResourceEndpointSecurityModel(sandModel,
                            api.getId().getApiName(), api.getId().getVersion(), api.getUuid(),
                            APIConstants.ENDPOINT_SECURITY_SANDBOX, resPrefix);
                    if (sandModel != null) {
                        securityModelMap.put(APIConstants.ENDPOINT_SECURITY_SANDBOX, sandModel);
                    }
                }

                resourceEndpointSecurityMap.put(entry.getKey(), securityModelMap);
            }
            if (!resourceEndpointSecurityMap.isEmpty()) {
                context.put("resourceEndpointSecurityMap", resourceEndpointSecurityMap);
            }
        } else if (apiProduct != null) {
            //Here we aggregate duplicate resourceURIs of an API and populate httpVerbs set in the uri template
            List<APIProductResource> productResources = new ArrayList<>(apiProduct.getProductResources());
            List<APIProductResource> aggregateResources = new ArrayList<>();
            List<String> uriTemplateNames = new ArrayList<String>();

            for (APIProductResource productResource : productResources) {
                URITemplate uriTemplate = productResource.getUriTemplate();
                String productResourceKey = productResource.getApiIdentifier() + ":" + uriTemplate.getUriTemplate();
                if (uriTemplateNames.contains(productResourceKey)) {
                    for (APIProductResource resource : aggregateResources) {
                        String resourceKey =
                                resource.getApiIdentifier() + ":" + resource.getUriTemplate().getUriTemplate();
                        if (resourceKey.equals(productResourceKey)) {
                            resource.getUriTemplate().setHttpVerbs(uriTemplate.getHTTPVerb());
                            resource.getUriTemplate()
                                    .setMediationScripts(uriTemplate.getHTTPVerb(), uriTemplate.getMediationScript());
                        }
                    }
                } else {
                    uriTemplate.setHttpVerbs(uriTemplate.getHTTPVerb());
                    aggregateResources.add(productResource);
                    uriTemplateNames.add(productResourceKey);
                }
            }
            context.put("apiType", apiProduct.getType());
            context.put("aggregates", aggregateResources);
        }

        return context;
    }

    private EndpointSecurityModel retrieveResourceEndpointSecurityModel(EndpointSecurityModel endpointSecurityModel,
                                                                        String apiName, String version, String apiId,
                                                                        String type, String prefix) {

        if (endpointSecurityModel != null && endpointSecurityModel.isEnabled()) {
            if (APIConstants.ENDPOINT_SECURITY_TYPE_OAUTH
                    .equalsIgnoreCase(endpointSecurityModel.getType())) {
                if (StringUtils.isNotEmpty(prefix)) {
                    endpointSecurityModel.setUniqueIdentifier(prefix.concat("--").concat(GatewayUtils
                            .retrieveUniqueIdentifier(apiId, type)));
                    endpointSecurityModel.setClientSecretAlias(prefix.concat("--").concat(GatewayUtils
                            .retrieveOauthClientSecretAlias(apiName, version, type)));
                    endpointSecurityModel.setPasswordAlias(prefix.concat("--").concat(GatewayUtils
                            .retrieveOAuthPasswordAlias(apiName, version, type)));
                    if (endpointSecurityModel.getProxyConfigs() != null && endpointSecurityModel.getProxyConfigs()
                            .isProxyEnabled()) {
                        endpointSecurityModel.getProxyConfigs().setProxyPasswordAlias(prefix.concat("--")
                                .concat(GatewayUtils.retrieveOAuthProxyPasswordAlias(apiName, version, type)));
                    }
                } else {
                    endpointSecurityModel.setUniqueIdentifier(GatewayUtils.retrieveUniqueIdentifier(apiId, type));
                    endpointSecurityModel.setClientSecretAlias(GatewayUtils.retrieveOauthClientSecretAlias(apiName,
                            version, type));
                    endpointSecurityModel.setPasswordAlias(GatewayUtils.retrieveOAuthPasswordAlias(apiName, version,
                            type));
                    if (endpointSecurityModel.getProxyConfigs() != null && endpointSecurityModel.getProxyConfigs()
                            .isProxyEnabled()) {
                        endpointSecurityModel.getProxyConfigs().setProxyPasswordAlias(GatewayUtils
                                .retrieveOAuthProxyPasswordAlias(apiName, version, type));
                    }
                }
            }
            if (StringUtils.isNotBlank(endpointSecurityModel.getUsername())
                    && StringUtils.isNotBlank(endpointSecurityModel.getPassword())) {
                endpointSecurityModel.setBase64EncodedPassword(new String(Base64.encodeBase64(
                        endpointSecurityModel.getUsername().concat(":")
                                .concat(endpointSecurityModel.getPassword()).getBytes())));
            }
            if (StringUtils.isNotEmpty(prefix)) {
                endpointSecurityModel.setAlias(prefix.concat("--")
                        .concat(GatewayUtils.retrieveBasicAuthAlias(apiName, version, type)));
            } else {
                endpointSecurityModel.setAlias(GatewayUtils.retrieveBasicAuthAlias(apiName, version, type));
            }
            return endpointSecurityModel;
        }
        return null;
    }
}
