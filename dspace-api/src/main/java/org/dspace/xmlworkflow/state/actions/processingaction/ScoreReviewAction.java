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
import java.util.Collections;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.lang.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.dspace.app.util.Util;
import org.dspace.authorize.AuthorizeException;
import org.dspace.content.MetadataFieldName;
import org.dspace.core.Context;
import org.dspace.services.ConfigurationService;
import org.dspace.services.factory.DSpaceServicesFactory;
import org.dspace.xmlworkflow.service.WorkflowRequirementsService;
import org.dspace.xmlworkflow.state.Step;
import org.dspace.xmlworkflow.state.actions.ActionAdvancedInfo;
import org.dspace.xmlworkflow.state.actions.ActionResult;
import org.dspace.xmlworkflow.storedcomponents.XmlWorkflowItem;

/**
 * This action allows multiple users to rate an item.
 * Supports decimal (float) scores instead of only integers.
 */
public class ScoreReviewAction extends ProcessingAction {
    private static final Logger log = LogManager.getLogger(ScoreReviewAction.class);

    private final ConfigurationService configurationService
            = DSpaceServicesFactory.getInstance().getConfigurationService();

    // Option(s)
    public static final String SUBMIT_SCORE = "submit_score";

    // Response param(s)
    private static final String SCORE = "score";
    private static final String REVIEW = "review";

    // Metadata fields to save params in
    public static final MetadataFieldName SCORE_FIELD =
        new MetadataFieldName(WorkflowRequirementsService.WORKFLOW_SCHEMA, SCORE, null);
    public static final MetadataFieldName REVIEW_FIELD =
        new MetadataFieldName(WorkflowRequirementsService.WORKFLOW_SCHEMA, REVIEW, null);

    // Whether or not it is required that a text review is added to the rating
    private boolean descriptionRequired;
    // Maximum value rating is allowed to be
    private double maxValue; // 🔹 Alterado de int → double para compatibilidade

    @Override
    public void activate(Context c, XmlWorkflowItem wf) {
        // empty
    }

    @Override
    public ActionResult execute(Context c, XmlWorkflowItem wfi, Step step, HttpServletRequest request)
        throws SQLException, AuthorizeException {
        if (super.isOptionInParam(request) &&
            StringUtils.equalsIgnoreCase(Util.getSubmitButton(request, SUBMIT_CANCEL), SUBMIT_SCORE)) {
            return processSetRating(c, wfi, request);
        }
        return new ActionResult(ActionResult.TYPE.TYPE_CANCEL);
    }

    private ActionResult processSetRating(Context c, XmlWorkflowItem wfi, HttpServletRequest request)
        throws SQLException, AuthorizeException {

        // 🔹 Aceita valores decimais
        double score = getDoubleParameter(request, SCORE);

        String review = request.getParameter(REVIEW);
        if (!this.checkRequestValid(score, review)) {
            return new ActionResult(ActionResult.TYPE.TYPE_ERROR);
        }

        // 🔹 Salva com o valor real (ex: "8.5") em vez de inteiro
        itemService.addMetadata(c, wfi.getItem(),
                SCORE_FIELD.schema, SCORE_FIELD.element, SCORE_FIELD.qualifier, null,
                String.valueOf(score));

        if (StringUtils.isNotBlank(review)) {
            itemService.addMetadata(c, wfi.getItem(),
                    REVIEW_FIELD.schema, REVIEW_FIELD.element, REVIEW_FIELD.qualifier, null,
                    String.format("%.2f - %s", score, review));
        }

        itemService.update(c, wfi.getItem());

        return new ActionResult(ActionResult.TYPE.TYPE_OUTCOME, ActionResult.OUTCOME_COMPLETE);
    }

    /**
     * Helper method to parse double safely.
     */
    private double getDoubleParameter(HttpServletRequest request, String param) {
        try {
            String value = request.getParameter(param);
            if (StringUtils.isNotBlank(value)) {
                return Double.parseDouble(value.replace(",", ".")); // 🔹 aceita vírgula ou ponto
            }
        } catch (NumberFormatException e) {
            log.warn("Invalid score format received: {}", request.getParameter(param));
        }
        return -1.0; // valor padrão de erro
    }

    /**
     * Request is not valid if:
     * - Given score is higher than configured maxValue
     * - There is no review given and description is configured to be required
     * Config in workflow-actions.xml
     */
    private boolean checkRequestValid(double score, String review) {
        if (score > this.maxValue) {
            log.error("{} only allows max rating {} (config workflow-actions.xml), given rating of " +
                "{} not allowed.", this.getClass().toString(), this.maxValue, score);
            return false;
        }
        if (StringUtils.isBlank(review) && this.descriptionRequired) {
            log.error("{} has config descriptionRequired=true (workflow-actions.xml), so rating " +
                "requests without 'review' query param containing description are not allowed",
                this.getClass().toString());
            return false;
        }
        return true;
    }

    @Override
    public List<String> getOptions() {
        List<String> options = new ArrayList<>();
        options.add(SUBMIT_SCORE);
        if (configurationService.getBooleanProperty("workflow.reviewer.file-edit", false)) {
            options.add(SUBMIT_EDIT_METADATA);
        }
        options.add(RETURN_TO_POOL);
        return options;
    }

    @Override
    protected List<String> getAdvancedOptions() {
        return Arrays.asList(SUBMIT_SCORE);
    }

    @Override
    protected List<ActionAdvancedInfo> getAdvancedInfo() {
        ScoreReviewActionAdvancedInfo info = new ScoreReviewActionAdvancedInfo();
        info.setDescriptionRequired(descriptionRequired);
        info.setMaxValue(maxValue);
        info.setType(SUBMIT_SCORE);
        info.generateId(SUBMIT_SCORE);
        return Collections.singletonList(info);
    }

    public void setDescriptionRequired(boolean descriptionRequired) {
        this.descriptionRequired = descriptionRequired;
    }

    public void setMaxValue(int maxValue) {
        this.maxValue = maxValue;
    }
}
