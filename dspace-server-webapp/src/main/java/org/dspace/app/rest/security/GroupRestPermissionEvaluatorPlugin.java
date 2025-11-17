/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.security;

import java.io.Serializable;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.dspace.app.rest.utils.ContextUtil;
import org.dspace.app.util.AuthorizeUtil;
import org.dspace.authorize.service.AuthorizeService;
import org.dspace.core.Constants;
import org.dspace.core.Context;
import org.dspace.eperson.EPerson;
import org.dspace.eperson.Group;
import org.dspace.eperson.service.EPersonService;
import org.dspace.eperson.service.GroupService;
import org.dspace.services.ConfigurationService;
import org.dspace.services.RequestService;
import org.dspace.services.factory.DSpaceServicesFactory;
import org.dspace.services.model.Request;
import org.dspace.xmlworkflow.storedcomponents.XmlWorkflowItem;
import org.dspace.xmlworkflow.storedcomponents.service.XmlWorkflowItemService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Extends default group permission evaluation.
 * 
 * Adds support so that:
 *  - Members of the "reviewmanagers" group
 *  - Submitters of active workflow items
 * can READ the reviewer group configured in action.selectrevieweraction.group (e.g. "Professores").
 */
@Component
public class GroupRestPermissionEvaluatorPlugin extends RestObjectPermissionEvaluatorPlugin {

    private static final Logger log = LogManager.getLogger();

    @Autowired
    private RequestService requestService;

    @Autowired
    private GroupService groupService;

    @Autowired
    private EPersonService ePersonService;

    @Autowired
    private XmlWorkflowItemService xmlWorkflowItemService;

    @Autowired
    private AuthorizeService authorizeService;

    private final ConfigurationService configurationService =
            DSpaceServicesFactory.getInstance().getConfigurationService();

    @Override
    public boolean hasDSpacePermission(Authentication authentication, Serializable targetId,
                                       String targetType, DSpaceRestPermission permission) {

        // Only evaluate GROUP READ access
        DSpaceRestPermission restPermission = DSpaceRestPermission.convert(permission);
        if (!DSpaceRestPermission.READ.equals(restPermission)
                || Constants.getTypeID(targetType) != Constants.GROUP) {
            return false;
        }

        if (targetId == null) {
            return false;
        }

        Request request = requestService.getCurrentRequest();
        Context context = ContextUtil.obtainContext(request.getHttpServletRequest());
        EPerson ePerson = context.getCurrentUser();

        try {
            UUID dsoId = UUID.fromString(targetId.toString());
            Group targetGroup = groupService.find(context, dsoId);

            if (targetGroup == null) {
                return false;
            }

            // Allow special system groups
            if (context.getSpecialGroups().contains(targetGroup)) {
                return true;
            }

            // Anonymous user cannot access
            if (ePerson == null) {
                return false;
            }

            // Allow members of the group itself
            if (groupService.isMember(context, ePerson, targetGroup)) {
                return true;
            }

            // Allow community or collection admins if permitted
            if (authorizeService.isCommunityAdmin(context) && AuthorizeUtil.canCommunityAdminManageAccounts()) {
                return true;
            }
            if (authorizeService.isCollectionAdmin(context) && AuthorizeUtil.canCollectionAdminManageAccounts()) {
                return true;
            }

            // --- ✅ Custom rule for reviewer workflows ---
            String reviewerGroupName = configurationService.getProperty("action.selectrevieweraction.group");
            if (reviewerGroupName == null) {
                reviewerGroupName = "Professores";
            }

            if (targetGroup.getName().equalsIgnoreCase(reviewerGroupName)) {

                // Allow if member of reviewmanagers
                Group reviewManagers = groupService.findByName(context, "reviewmanagers");
                if (reviewManagers != null && groupService.isMember(context, ePerson, reviewManagers)) {
                    return true;
                }

                // Allow if submitter of any active workflow item
                List<XmlWorkflowItem> wfItems = xmlWorkflowItemService.findBySubmitter(context, ePerson);
                if (wfItems != null && !wfItems.isEmpty()) {
                    return true;
                }
            }

        } catch (SQLException e) {
            log.error("Error evaluating Group permission: {}", e.getMessage(), e);
        }

        return false;
    }
}
