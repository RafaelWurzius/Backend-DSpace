/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.repository;

import static org.apache.commons.lang3.StringUtils.isBlank;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.dspace.app.rest.Parameter;
import org.dspace.app.rest.SearchRestMethod;
import org.dspace.app.rest.converter.MetadataConverter;
import org.dspace.app.rest.exception.GroupNameNotProvidedException;
import org.dspace.app.rest.exception.RepositoryMethodNotImplementedException;
import org.dspace.app.rest.exception.UnprocessableEntityException;
import org.dspace.app.rest.model.EPersonRest;
import org.dspace.app.rest.model.GroupRest;
import org.dspace.app.rest.model.patch.Patch;
import org.dspace.authorize.AuthorizeException;
import org.dspace.authorize.service.AuthorizeService;
import org.dspace.content.DSpaceObject;
import org.dspace.core.Context;
import org.dspace.eperson.EPerson;
import org.dspace.eperson.Group;
import org.dspace.eperson.service.GroupService;
import org.dspace.xmlworkflow.storedcomponents.XmlWorkflowItem;
import org.dspace.xmlworkflow.storedcomponents.service.XmlWorkflowItemService;
import org.dspace.services.ConfigurationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.rest.webmvc.ResourceNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;

/**
 * This is the repository responsible to manage Group Rest object
 *
 * @author Andrea Bollini
 * Modified to allow submitter/reviewmanager access to reviewer group members.
 */

@Component(GroupRest.CATEGORY + "." + GroupRest.PLURAL_NAME)
public class GroupRestRepository extends DSpaceObjectRestRepository<Group, GroupRest> {

    @Autowired
    private GroupService gs;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private MetadataConverter metadataConverter;

    @Autowired
    private AuthorizeService authorizeService;

    @Autowired
    private XmlWorkflowItemService xmlWorkflowItemService;

    @Autowired
    private ConfigurationService configurationService;

    @Autowired
    GroupRestRepository(GroupService dsoService) {
        super(dsoService);
        this.gs = dsoService;
    }

    @Override
    @PreAuthorize("hasAuthority('ADMIN')")
    protected GroupRest createAndReturn(Context context)
            throws AuthorizeException, RepositoryMethodNotImplementedException {

        HttpServletRequest req = getRequestService().getCurrentRequest().getHttpServletRequest();
        GroupRest groupRest;

        try {
            groupRest = mapper.readValue(req.getInputStream(), GroupRest.class);
        } catch (IOException excIO) {
            throw new UnprocessableEntityException("error parsing the body ..." + excIO.getMessage());
        }

        if (isBlank(groupRest.getName())) {
            throw new GroupNameNotProvidedException();
        }

        Group group;
        try {
            group = gs.create(context);
            gs.setName(group, groupRest.getName());
            gs.update(context, group);
            metadataConverter.setMetadata(context, group, groupRest.getMetadata());
        } catch (SQLException excSQL) {
            throw new RuntimeException(excSQL.getMessage(), excSQL);
        }

        return converter.toRest(group, utils.obtainProjection());
    }

    @Override
    @PreAuthorize("hasPermission(#id, 'GROUP', 'READ')")
    public GroupRest findOne(Context context, UUID id) {
        Group group = null;
        try {
            group = gs.find(context, id);
        } catch (SQLException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
        if (group == null) {
            return null;
        }
        return converter.toRest(group, utils.obtainProjection());
    }

    /**
     * Custom method to expose members of a group (GET /api/eperson/groups/{uuid}/epersons)
     * allowing submitter and reviewmanagers to see reviewer group members.
     */
    @PreAuthorize("isAuthenticated()")
    public Page<EPersonRest> getGroupMembers(UUID groupId, Pageable pageable) {
        Context context = obtainContext();
        EPerson currentUser = context.getCurrentUser();

        try {
            Group group = gs.find(context, groupId);
            if (group == null) {
                throw new ResourceNotFoundException("Group not found: " + groupId);
            }

            // Admins always can
            if (authorizeService.isAdmin(context)) {
                return getMembersPage(context, group, pageable);
            }

            // If user is member of group itself, allow
            if (gs.isMember(context, currentUser, group)) {
                return getMembersPage(context, group, pageable);
            }

            // Special: allow submitter and reviewmanagers to read reviewer group members
            String reviewerGroupName = configurationService.getProperty("action.selectrevieweraction.group");
            if (reviewerGroupName != null && group.getName().equalsIgnoreCase(reviewerGroupName)) {

                // Allow submitter with any workflow item
                List<XmlWorkflowItem> wfItems = xmlWorkflowItemService.findBySubmitter(context, currentUser);
                if (wfItems != null && !wfItems.isEmpty()) {
                    return getMembersPage(context, group, pageable);
                }

                // Allow reviewmanagers
                Group reviewManagers = gs.findByName(context, "reviewmanagers");
                if (reviewManagers != null && gs.isMember(context, currentUser, reviewManagers)) {
                    return getMembersPage(context, group, pageable);
                }
            }

            throw new AccessDeniedException("Access denied to group members");

        } catch (SQLException e) {
            throw new RuntimeException("Error accessing group members", e);
        }
    }

    private Page<EPersonRest> getMembersPage(Context context, Group group, Pageable pageable) throws SQLException {
        List<EPerson> members = gs.allMembers(context, group);
        long total = members.size();
        return converter.toRestPage(members, pageable, total, utils.obtainProjection());
    }

    @PreAuthorize("hasAuthority('ADMIN')")
    @Override
    public Page<GroupRest> findAll(Context context, Pageable pageable) {
        try {
            long total = gs.countTotal(context);
            List<Group> groups = gs.findAll(context, null, pageable.getPageSize(),
                                            Math.toIntExact(pageable.getOffset()));
            return converter.toRestPage(groups, pageable, total, utils.obtainProjection());
        } catch (SQLException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    @PreAuthorize("hasPermission(#id, 'GROUP', 'WRITE')")
    protected void patch(Context context, HttpServletRequest request, String apiCategory, String model, UUID id,
                         Patch patch) throws AuthorizeException, SQLException {
        patchDSpaceObject(apiCategory, model, id, patch);
    }

    @PreAuthorize("hasAuthority('ADMIN') || hasAuthority('MANAGE_ACCESS_GROUP')")
    @SearchRestMethod(name = "byMetadata")
    public Page<GroupRest> findByMetadata(@Parameter(value = "query", required = true) String query,
                                          Pageable pageable) {
        try {
            Context context = obtainContext();
            long total = gs.searchResultCount(context, query);
            List<Group> groups = gs.search(context, query, Math.toIntExact(pageable.getOffset()),
                                                           Math.toIntExact(pageable.getPageSize()));
            return converter.toRestPage(groups, pageable, total, utils.obtainProjection());
        } catch (SQLException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @PreAuthorize("hasAuthority('ADMIN') || hasAuthority('MANAGE_ACCESS_GROUP')")
    @SearchRestMethod(name = "isNotMemberOf")
    public Page<GroupRest> findIsNotMemberOf(@Parameter(value = "group", required = true) UUID groupUUID,
                                             @Parameter(value = "query", required = true) String query,
                                             Pageable pageable) {
        try {
            Context context = obtainContext();
            Group excludeParentGroup = gs.find(context, groupUUID);
            long total = gs.searchNonMembersCount(context, query, excludeParentGroup);
            List<Group> groups = gs.searchNonMembers(context, query, excludeParentGroup,
                                                     Math.toIntExact(pageable.getOffset()),
                                                     Math.toIntExact(pageable.getPageSize()));
            return converter.toRestPage(groups, pageable, total, utils.obtainProjection());
        } catch (SQLException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public Class<GroupRest> getDomainClass() {
        return GroupRest.class;
    }

    @Override
    @PreAuthorize("hasAuthority('ADMIN')")
    protected void delete(Context context, UUID uuid) throws AuthorizeException {
        Group group = null;
        try {
            group = gs.find(context, uuid);
            if (group == null) {
                throw new ResourceNotFoundException(
                        GroupRest.CATEGORY + "." + GroupRest.NAME
                                + " with id: " + uuid + " not found"
                );
            }
            try {
                if (group.isPermanent()) {
                    throw new UnprocessableEntityException("A permanent group cannot be deleted");
                }
                final DSpaceObject parentObject = gs.getParentObject(context, group);
                if (parentObject != null) {
                    throw new UnprocessableEntityException(
                            "This group cannot be deleted"
                                    + " as it has a parent " + parentObject.getType()
                                    + " with id " + parentObject.getID());
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
            gs.delete(context, group);
        } catch (SQLException | IOException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }
}
