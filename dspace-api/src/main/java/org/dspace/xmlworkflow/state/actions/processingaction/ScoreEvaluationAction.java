/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.xmlworkflow.state.actions.processingaction;

import static org.dspace.xmlworkflow.state.actions.processingaction.ScoreReviewAction.REVIEW_FIELD;
import static org.dspace.xmlworkflow.state.actions.processingaction.ScoreReviewAction.SCORE_FIELD;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import org.dspace.authorize.AuthorizeException;
import org.dspace.content.Item;
import org.dspace.content.MetadataSchemaEnum;
import org.dspace.content.MetadataValue;
import org.dspace.core.Context;
import org.dspace.xmlworkflow.factory.XmlWorkflowServiceFactory;
import org.dspace.xmlworkflow.state.Step;
import org.dspace.xmlworkflow.state.actions.ActionResult;
import org.dspace.xmlworkflow.storedcomponents.XmlWorkflowItem;

/**
 * Processing class for the score evaluation action.
 * This action evaluates the mean score of reviewers. If the mean score
 * is higher than the configured minimum, the item proceeds; otherwise, it is rejected.
 *
 * Now supports decimal (floating-point) scores, e.g. 8.5, 9.25, etc.
 */
public class ScoreEvaluationAction extends ProcessingAction {

    // Minimum aggregate of scores (can be decimal now)
    private double minimumAcceptanceScore;

    @Override
    public void activate(Context c, XmlWorkflowItem wf) {
        // No activation logic needed
    }

    @Override
    public ActionResult execute(Context c, XmlWorkflowItem wfi, Step step, HttpServletRequest request)
        throws SQLException, AuthorizeException, IOException {

        // Retrieve the mean score (now supports decimals)
        double scoreMean = getMeanScore(wfi);

        // Check if item passes the minimum required score
        boolean hasPassed = scoreMean >= minimumAcceptanceScore;

        // Clear all score metadata after processing
        itemService.clearMetadata(c, wfi.getItem(), SCORE_FIELD.schema, SCORE_FIELD.element, SCORE_FIELD.qualifier, Item.ANY);

        if (hasPassed) {
            addRatingInfoToProv(c, wfi, scoreMean);
            return new ActionResult(ActionResult.TYPE.TYPE_OUTCOME, ActionResult.OUTCOME_COMPLETE);
        } else {
            XmlWorkflowServiceFactory.getInstance().getXmlWorkflowService()
                .sendWorkflowItemBackSubmission(c, wfi, c.getCurrentUser(), getProvenanceStartId(),
                    "The item was rejected due to a low review score.");
            return new ActionResult(ActionResult.TYPE.TYPE_SUBMISSION_PAGE);
        }
    }

    /**
     * Calculates the mean (average) score of all reviewer ratings.
     * Supports decimal values such as 8.5 or 9.25.
     */
    private double getMeanScore(XmlWorkflowItem wfi) {
        List<MetadataValue> scores = itemService
            .getMetadata(wfi.getItem(), SCORE_FIELD.schema, SCORE_FIELD.element, SCORE_FIELD.qualifier, Item.ANY);

        if (scores.isEmpty()) {
            return 0.0;
        }

        double totalScore = 0.0;
        int validScores = 0;

        for (MetadataValue score : scores) {
            try {
                totalScore += Double.parseDouble(score.getValue());
                validScores++;
            } catch (NumberFormatException e) {
                // Simply skip invalid or malformed score values
            }
        }

        return validScores > 0 ? totalScore / validScores : 0.0;
    }

    /**
     * Adds a provenance entry with the average score and review notes.
     */
    private void addRatingInfoToProv(Context c, XmlWorkflowItem wfi, double scoreMean)
        throws SQLException, AuthorizeException {

        StringBuilder provDescription = new StringBuilder();
        provDescription.append(String.format("%s Approved for entry into archive with a score of: %.2f",
            getProvenanceStartId(), scoreMean));

        List<MetadataValue> reviews = itemService
            .getMetadata(wfi.getItem(), REVIEW_FIELD.schema, REVIEW_FIELD.element, REVIEW_FIELD.qualifier, Item.ANY);

        if (!reviews.isEmpty()) {
            provDescription.append(" | Reviews: ");
            for (MetadataValue review : reviews) {
                provDescription.append(String.format("; %s", review.getValue()));
            }
        }

        c.turnOffAuthorisationSystem();
        itemService.addMetadata(c, wfi.getItem(),
            MetadataSchemaEnum.DC.getName(), "description", "provenance", "en",
            provDescription.toString());
        itemService.update(c, wfi.getItem());
        c.restoreAuthSystemState();
    }

    @Override
    public List<String> getOptions() {
        List<String> options = new ArrayList<>();
        options.add(RETURN_TO_POOL);
        return options;
    }

    public double getMinimumAcceptanceScore() {
        return minimumAcceptanceScore;
    }

    public void setMinimumAcceptanceScore(double minimumAcceptanceScore) {
        this.minimumAcceptanceScore = minimumAcceptanceScore;
    }
}
