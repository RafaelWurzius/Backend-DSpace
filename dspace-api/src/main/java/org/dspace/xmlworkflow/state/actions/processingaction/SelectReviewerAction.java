/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.xmlworkflow.state.actions.processingaction;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.annotation.Nullable;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.dspace.app.util.Util;
import org.dspace.authorize.AuthorizeException;
import org.dspace.core.Context;
import org.dspace.eperson.EPerson;
import org.dspace.eperson.Group;
import org.dspace.eperson.service.EPersonService;
import org.dspace.eperson.service.GroupService;
import org.dspace.services.ConfigurationService;
import org.dspace.xmlworkflow.Role;
import org.dspace.xmlworkflow.state.Step;
import org.dspace.xmlworkflow.state.actions.ActionAdvancedInfo;
import org.dspace.xmlworkflow.state.actions.ActionResult;
import org.dspace.xmlworkflow.storedcomponents.WorkflowItemRole;
import org.dspace.xmlworkflow.storedcomponents.XmlWorkflowItem;
import org.dspace.xmlworkflow.storedcomponents.service.WorkflowItemRoleService;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Processing class for an action where an assigned user can
 * assign reviewers and optionally an advisor (orientador) to a workflow item.
 *
 * Extended by Rafael W to support advisor assignment.
 */
public class SelectReviewerAction extends ProcessingAction {

    private static final Logger log = LogManager.getLogger(SelectReviewerAction.class);

    private static final String SUBMIT_CANCEL = "submit_cancel";
    private static final String SUBMIT_SELECT_REVIEWER = "submit_select_reviewer";
    private static final String PARAM_REVIEWER = "eperson";
    private static final String PARAM_ADVISOR = "advisor"; // novo parâmetro para o orientador

    private static final String CONFIG_REVIEWER_GROUP = "action.selectrevieweraction.group";

    private Role role;

    @Autowired
    private EPersonService ePersonService;

    @Autowired
    private WorkflowItemRoleService workflowItemRoleService;

    @Autowired
    private ConfigurationService configurationService;

    @Autowired
    private GroupService groupService;

    private static Group selectFromReviewsGroup;
    private static boolean selectFromReviewsGroupInitialised = false;

    @Override
    public void activate(Context c, XmlWorkflowItem wf) {
        // nothing here
    }

    @Override
    public ActionResult execute(Context c, XmlWorkflowItem wfi, Step step, HttpServletRequest request)
        throws SQLException, AuthorizeException {

        EPerson currentUser = c.getCurrentUser();
        if (currentUser != null && wfi.getSubmitter() != null &&
            currentUser.getID().equals(wfi.getSubmitter().getID())) {
            log.info("Submitter {} executando SelectReviewerAction no item {}", currentUser.getEmail(), wfi.getID());
        } else {
            log.debug("Usuário {} não é o submitter do item {}",
                currentUser != null ? currentUser.getEmail() : "desconhecido", wfi.getID());
        }

        String submitButton = Util.getSubmitButton(request, SUBMIT_CANCEL);

        if (submitButton.equals(SUBMIT_CANCEL)) {
            return new ActionResult(ActionResult.TYPE.TYPE_CANCEL);
        } else if (submitButton.startsWith(SUBMIT_SELECT_REVIEWER)) {
            try {
                c.turnOffAuthorisationSystem();
                return processSelectReviewers(c, wfi, request);
            } finally {
                c.restoreAuthSystemState();
            }
        }

        return new ActionResult(ActionResult.TYPE.TYPE_ERROR);
    }

    /**
     * Processa a seleção de revisores e a atribuição do orientador (advisor).
     */
    private ActionResult processSelectReviewers(Context c, XmlWorkflowItem wfi, HttpServletRequest request)
        throws SQLException, AuthorizeException {

        // === 1) Processar revisores ===
        String[] reviewerIds = request.getParameterValues(PARAM_REVIEWER);
        if (ArrayUtils.isEmpty(reviewerIds)) {
            log.warn("Nenhum revisor selecionado para o item {}", wfi.getID());
            return new ActionResult(ActionResult.TYPE.TYPE_ERROR);
        }

        List<EPerson> reviewers = new ArrayList<>();
        for (String reviewerId : reviewerIds) {
            EPerson reviewer = ePersonService.find(c, UUID.fromString(reviewerId));
            if (reviewer == null) {
                log.warn("EPerson não encontrado com UUID {}", reviewerId);
            } else {
                reviewers.add(reviewer);
            }
        }

        if (!this.checkReviewersValid(c, reviewers)) {
            return new ActionResult(ActionResult.TYPE.TYPE_ERROR);
        }

        createWorkflowItemRole(c, wfi, reviewers);

        // === 2) Processar orientador (advisor) ===
        String advisorId = request.getParameter(PARAM_ADVISOR);
        if (StringUtils.isNotBlank(advisorId)) {
            try {
                EPerson advisor = ePersonService.find(c, UUID.fromString(advisorId));
                if (advisor == null) {
                    log.warn("Advisor não encontrado para UUID {}", advisorId);
                } else {
                    assignAdvisorRole(c, wfi, advisor);
                }
            } catch (IllegalArgumentException ex) {
                log.warn("UUID inválido para orientador: {}", advisorId);
            }
        } else {
            log.info("Nenhum orientador foi selecionado para o item {}", wfi.getID());
        }

        return new ActionResult(ActionResult.TYPE.TYPE_OUTCOME, ActionResult.OUTCOME_COMPLETE);
    }

    /**
     * Atribui o orientador (advisor) ao WorkflowItem, criando ou atualizando a role correspondente.
     */
    private void assignAdvisorRole(Context c, XmlWorkflowItem wfi, EPerson advisor)
        throws SQLException, AuthorizeException {

        String advisorRoleId = "advisor"; // deve corresponder ao ID definido no workflow.xml

        List<WorkflowItemRole> roles = workflowItemRoleService.findByWorkflowItem(c, wfi);
        Optional<WorkflowItemRole> existing = roles.stream()
            .filter(r -> advisorRoleId.equals(r.getRoleId()))
            .findFirst();

        if (existing.isPresent()) {
            WorkflowItemRole wir = existing.get();
            if (wir.getEPerson() == null ||
                !advisor.getID().equals(wir.getEPerson().getID())) {
                wir.setEPerson(advisor);
                workflowItemRoleService.update(c, wir);
                log.info("Orientador atualizado para o item {}: {}", wfi.getID(), advisor.getEmail());
            } else {
                log.info("Orientador já associado corretamente ao item {}", wfi.getID());
            }
        } else {
            WorkflowItemRole wir = workflowItemRoleService.create(c);
            wir.setRoleId(advisorRoleId);
            wir.setWorkflowItem(wfi);
            wir.setEPerson(advisor);
            workflowItemRoleService.update(c, wir);
            log.info("Orientador {} associado ao item {}", advisor.getEmail(), wfi.getID());
        }
    }

    private boolean checkReviewersValid(Context c, List<EPerson> reviewers) throws SQLException {
        if (reviewers.isEmpty()) {
            return false;
        }
        Group group = this.getGroup(c);
        if (group != null) {
            for (EPerson reviewer : reviewers) {
                if (!groupService.isMember(c, reviewer, group)) {
                    log.error("Reviewer {} não pertence ao grupo {}", reviewer.getEmail(), group.getID());
                    return false;
                }
            }
        }
        return true;
    }

    private WorkflowItemRole createWorkflowItemRole(Context c, XmlWorkflowItem wfi, List<EPerson> reviewers)
        throws SQLException, AuthorizeException {

        WorkflowItemRole workflowItemRole = workflowItemRoleService.create(c);
        workflowItemRole.setRoleId(getRole().getId());
        workflowItemRole.setWorkflowItem(wfi);

        if (reviewers.size() == 1) {
            workflowItemRole.setEPerson(reviewers.get(0));
        } else {
            c.turnOffAuthorisationSystem();
            Group selectedReviewsGroup = groupService.create(c);
            groupService.setName(selectedReviewsGroup, "selectedReviewsGroup_" + wfi.getID());
            for (EPerson reviewer : reviewers) {
                groupService.addMember(c, selectedReviewsGroup, reviewer);
            }
            workflowItemRole.setGroup(selectedReviewsGroup);
            c.restoreAuthSystemState();
        }

        workflowItemRoleService.update(c, workflowItemRole);
        return workflowItemRole;
    }

    @Override
    public List<String> getOptions() {
        List<String> options = new ArrayList<>();
        options.add(SUBMIT_SELECT_REVIEWER);
        options.add(RETURN_TO_POOL);
        return options;
    }

    @Override
    protected List<String> getAdvancedOptions() {
        return Arrays.asList(SUBMIT_SELECT_REVIEWER);
    }

    /**
     * Adiciona informações avançadas para a UI (inclusive que deve mostrar campo de orientador).
     */
    @Override
    protected List<ActionAdvancedInfo> getAdvancedInfo() {
        List<ActionAdvancedInfo> advancedInfo = new ArrayList<>();
        SelectReviewerActionAdvancedInfo info = new SelectReviewerActionAdvancedInfo();

        if (getGroup(null) != null) {
            info.setGroup(getGroup(null).getID().toString());
        }

        info.setType(SUBMIT_SELECT_REVIEWER);
        info.setAdvisorRequired(true); // <- informa ao frontend que o campo de orientador é obrigatório
        info.generateId(SUBMIT_SELECT_REVIEWER);

        advancedInfo.add(info);
        return advancedInfo;
    }

    public Role getRole() {
        return role;
    }

    @Autowired
    public void setRole(Role role) {
        this.role = role;
    }

    private Group getGroup(@Nullable Context context) {
        if (selectFromReviewsGroupInitialised) {
            return this.selectFromReviewsGroup;
        }
        if (context == null) {
            context = new Context();
        }
        String groupIdOrName = configurationService.getProperty(CONFIG_REVIEWER_GROUP);

        if (StringUtils.isNotBlank(groupIdOrName)) {
            Group group = null;
            try {
                group = groupService.findByName(context, groupIdOrName);
                if (group == null) {
                    group = groupService.find(context, UUID.fromString(groupIdOrName));
                }
            } catch (Exception e) {
                log.error("Erro ao determinar o grupo de revisores configurado: {}={}",
                    CONFIG_REVIEWER_GROUP, groupIdOrName);
            }

            this.selectFromReviewsGroup = group;
        }
        selectFromReviewsGroupInitialised = true;
        return this.selectFromReviewsGroup;
    }

    public static void resetGroup() {
        selectFromReviewsGroup = null;
        selectFromReviewsGroupInitialised = false;
    }
}
