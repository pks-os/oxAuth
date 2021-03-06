/*
 * oxAuth is available under the MIT License (2008). See http://opensource.org/licenses/MIT for full text.
 *
 * Copyright (c) 2014, Gluu
 */

package org.xdi.oxauth.uma.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import javax.ejb.Stateless;
import javax.inject.Inject;
import javax.inject.Named;

import org.apache.commons.lang.ArrayUtils;
import org.gluu.persist.PersistenceEntryManager;
import org.gluu.persist.model.BatchOperation;
import org.gluu.persist.model.ProcessBatchOperation;
import org.gluu.persist.model.SearchScope;
import org.gluu.persist.model.base.SimpleBranch;
import org.gluu.search.filter.Filter;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.slf4j.Logger;
import org.xdi.oxauth.model.common.AuthorizationGrantList;
import org.xdi.oxauth.model.config.StaticConfiguration;
import org.xdi.oxauth.model.config.WebKeysConfiguration;
import org.xdi.oxauth.model.configuration.AppConfiguration;
import org.xdi.oxauth.model.crypto.signature.SignatureAlgorithm;
import org.xdi.oxauth.model.jwt.Jwt;
import org.xdi.oxauth.model.registration.Client;
import org.xdi.oxauth.model.token.JwtSigner;
import org.xdi.oxauth.model.uma.persistence.UmaPermission;
import org.xdi.oxauth.model.util.Util;
import org.xdi.oxauth.service.CleanerTimer;
import org.xdi.oxauth.service.ClientService;
import org.xdi.oxauth.uma.authorization.UmaPCT;
import org.xdi.oxauth.uma.authorization.UmaRPT;
import org.xdi.oxauth.util.ServerUtil;
import org.xdi.util.INumGenerator;
import org.xdi.util.StringHelper;

import com.google.common.base.Preconditions;

/**
 * RPT manager component
 *
 * @author Yuriy Zabrovarnyy
 * @author Javier Rojas Blum
 * @version June 28, 2017
 */
@Stateless
@Named
public class UmaRptService {

    private static final String ORGUNIT_OF_RPT = "uma_rpt";

    public static final int DEFAULT_RPT_LIFETIME = 3600;

    @Inject
    private Logger log;

    @Inject
    private PersistenceEntryManager ldapEntryManager;

    @Inject
    private WebKeysConfiguration webKeysConfiguration;

    @Inject
    private UmaPctService pctService;

    @Inject
    private UmaScopeService umaScopeService;

    @Inject
    private AppConfiguration appConfiguration;

    @Inject
    private StaticConfiguration staticConfiguration;

    @Inject
    private ClientService clientService;

    public static String getDn(String clientDn, String uniqueIdentifier) {
        return String.format("uniqueIdentifier=%s,%s", uniqueIdentifier, branchDn(clientDn));
    }

    public static String branchDn(String clientDn) {
        return String.format("ou=%s,%s", ORGUNIT_OF_RPT, clientDn);
    }

    public void persist(UmaRPT rpt) {
        try {
            Preconditions.checkNotNull(rpt.getClientId());

            Client client = clientService.getClient(rpt.getClientId());

            addBranchIfNeeded(client.getDn());
            String id = UUID.randomUUID().toString();
            rpt.setId(id);
            rpt.setDn(getDn(client.getDn(), id));
            ldapEntryManager.persist(rpt);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }

    public UmaRPT getRPTByCode(String rptCode) {
        try {
            final Filter filter = Filter.create(String.format("&(oxAuthTokenCode=%s)", rptCode));
            final String baseDn = staticConfiguration.getBaseDn().getClients();
            final List<UmaRPT> entries = ldapEntryManager.findEntries(baseDn, UmaRPT.class, filter);
            if (entries != null && !entries.isEmpty()) {
                return entries.get(0);
            } else {
                log.error("Failed to find RPT by code: " + rptCode);
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
        return null;
    }

    public void deleteByCode(String rptCode) {
        try {
            final UmaRPT t = getRPTByCode(rptCode);
            if (t != null) {
                ldapEntryManager.remove(t);
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }

    public void cleanup(final Date now) {
    	BatchOperation<UmaRPT> rptBatchService = new ProcessBatchOperation<UmaRPT>() {
            @Override
            public void performAction(List<UmaRPT> entries) {
                for (UmaRPT p : entries) {
                    try {
                        ldapEntryManager.remove(p);
                    } catch (Exception e) {
                        log.error("Failed to remove entry", e);
                    }
                }
            }
        };
        ldapEntryManager.findEntries(staticConfiguration.getBaseDn().getClients(), UmaRPT.class, getExpiredUmaRptFilter(now), SearchScope.SUB, new String[] { "" }, rptBatchService, 0, 0, CleanerTimer.BATCH_SIZE);
    }

    private Filter getExpiredUmaRptFilter(Date date) {
        return Filter.createLessOrEqualFilter("oxAuthExpiration", ldapEntryManager.encodeTime(date));
    }

    public void addPermissionToRPT(UmaRPT rpt, Collection<UmaPermission> permissions) {
        addPermissionToRPT(rpt, permissions.toArray(new UmaPermission[permissions.size()]));
    }

    public void addPermissionToRPT(UmaRPT rpt, UmaPermission... permission) {
        if (ArrayUtils.isEmpty(permission)) {
            return;
        }

        final List<String> permissions = getPermissionDns(Arrays.asList(permission));
        if (rpt.getPermissions() != null) {
            permissions.addAll(rpt.getPermissions());
        }

        rpt.setPermissions(permissions);

        try {
            ldapEntryManager.merge(rpt);
            log.trace("Persisted RPT: " + rpt);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }

    public static List<String> getPermissionDns(Collection<UmaPermission> permissions) {
        final List<String> result = new ArrayList<String>();
        if (permissions != null) {
            for (UmaPermission p : permissions) {
                result.add(p.getDn());
            }
        }
        return result;
    }

    public List<UmaPermission> getRptPermissions(UmaRPT p_rpt) {
        final List<UmaPermission> result = new ArrayList<UmaPermission>();
        try {
            if (p_rpt != null && p_rpt.getPermissions() != null) {
                final List<String> permissionDns = p_rpt.getPermissions();
                for (String permissionDn : permissionDns) {
                    final UmaPermission permissionObject = ldapEntryManager.find(UmaPermission.class, permissionDn);
                    if (permissionObject != null) {
                        result.add(permissionObject);
                    }
                }
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
        return result;
    }

    public Date rptExpirationDate() {
        int lifeTime = appConfiguration.getUmaRptLifetime();
        if (lifeTime <= 0) {
            lifeTime = DEFAULT_RPT_LIFETIME;
        }

        final Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.SECOND, lifeTime);
        return calendar.getTime();
    }

    public UmaRPT createRPTAndPersist(Client client, List<UmaPermission> permissions) {
        try {
            final Date creationDate = new Date();
            final Date expirationDate = rptExpirationDate();

            final String code;
            if (client.isRptAsJwt()) {
                code = createRptJwt(client, permissions, creationDate, expirationDate);
            } else {
                code = UUID.randomUUID().toString() + "_" + INumGenerator.generate(8);
            }

            UmaRPT rpt = new UmaRPT(code, creationDate, expirationDate, null, client.getClientId());
            rpt.setPermissions(getPermissionDns(permissions));
            persist(rpt);
            return rpt;
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            throw new RuntimeException("Failed to generate RPT, clientId: " + client.getClientId(), e);
        }
    }

    private String createRptJwt(Client client, List<UmaPermission> permissions, Date creationDate, Date expirationDate) throws Exception {
        SignatureAlgorithm signatureAlgorithm = SignatureAlgorithm.fromString(appConfiguration.getDefaultSignatureAlgorithm());
        if (client.getAccessTokenSigningAlg() != null && SignatureAlgorithm.fromString(client.getAccessTokenSigningAlg()) != null) {
            signatureAlgorithm = SignatureAlgorithm.fromString(client.getAccessTokenSigningAlg());
        }

        final JwtSigner jwtSigner = new JwtSigner(appConfiguration, webKeysConfiguration, signatureAlgorithm, client.getClientId(), clientService.decryptSecret(client.getClientSecret()));
        final Jwt jwt = jwtSigner.newJwt();
        jwt.getClaims().setClaim("client_id", client.getClientId());
        jwt.getClaims().setExpirationTime(expirationDate);
        jwt.getClaims().setIssuedAt(creationDate);
        jwt.getClaims().setAudience(client.getClientId());

        if (permissions != null && !permissions.isEmpty()) {
            String pctCode = permissions.iterator().next().getAttributes().get(UmaPermission.PCT);
            if (StringHelper.isNotEmpty(pctCode)) {
                UmaPCT pct = pctService.getByCode(pctCode);
                if (pct != null) {
                    jwt.getClaims().setClaim("pct_claims", pct.getClaims().toJsonObject());
                } else {
                    log.error("Failed to find PCT with code: " + pctCode + " which is taken from permission object: " + permissions.iterator().next().getDn());
                }
            }

            jwt.getClaims().setClaim("permissions", buildPermissionsJSONObject(permissions));
        }
        return jwtSigner.sign().toString();
    }

    public JSONArray buildPermissionsJSONObject(List<UmaPermission> permissions) throws IOException, JSONException {
        List<org.xdi.oxauth.model.uma.UmaPermission> result = new ArrayList<org.xdi.oxauth.model.uma.UmaPermission>();

        for (UmaPermission permission : permissions) {
            permission.checkExpired();
            permission.isValid();
            if (permission.isValid()) {
                final org.xdi.oxauth.model.uma.UmaPermission toAdd = ServerUtil.convert(permission, umaScopeService);
                if (toAdd != null) {
                    result.add(toAdd);
                }
            } else {
                log.debug("Ignore permission, skip it in response because permission is not valid. Permission dn: {}", permission.getDn());
            }
        }

        final String json = ServerUtil.asJson(result);
        return new JSONArray(json);
    }

    public UmaPermission getPermissionFromRPTByResourceId(UmaRPT rpt, String resourceId) {
        try {
            if (Util.allNotBlank(resourceId)) {
                for (UmaPermission permission : getRptPermissions(rpt)) {
                    if (resourceId.equals(permission.getResourceId())) {
                        return permission;
                    }
                }
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
        return null;
    }

    public void addBranch(String clientDn) {
        final SimpleBranch branch = new SimpleBranch();
        branch.setOrganizationalUnitName(ORGUNIT_OF_RPT);
        branch.setDn(branchDn(clientDn));
        ldapEntryManager.persist(branch);
    }

    public void addBranchIfNeeded(String clientDn) {
        if (!containsBranch(clientDn)) {
            addBranch(clientDn);
        }
    }

    public boolean containsBranch(String clientDn) {
        return ldapEntryManager.contains(SimpleBranch.class, branchDn(clientDn));
    }

//    private JsonWebResponse createJwr(UmaRPT rpt, String authorization, List<String> gluuAccessTokenScopes) throws Exception {
//        final AuthorizationGrant grant = tokenService.getAuthorizationGrant(authorization);
//
//        JwtSigner jwtSigner = JwtSigner.newJwtSigner(appConfiguration, webKeysConfiguration, grant.getClient());
//        Jwt jwt = jwtSigner.newJwt();
//
//        jwt.getClaims().setExpirationTime(rpt.getExpirationDate());
//        jwt.getClaims().setIssuedAt(rpt.getCreationDate());
//
//        if (!gluuAccessTokenScopes.isEmpty()) {
//            jwt.getClaims().setClaim("scopes", gluuAccessTokenScopes);
//        }
//
//        return jwtSigner.sign();
//    }

//    UmaRPT rpt = rptService.createRPT(authorization);
//
//    String rptResponse = rpt.getCode();
//    final Boolean umaRptAsJwt = appConfiguration.getUmaRptAsJwt();
//    if (umaRptAsJwt != null && umaRptAsJwt) {
//        rptResponse = createJwr(rpt, authorization, Lists.<String>newArrayList()).asString();
//    }

}
