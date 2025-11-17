/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.xmlworkflow.state.actions.processingaction;

import org.dspace.xmlworkflow.state.actions.ActionAdvancedInfo;
import org.springframework.util.DigestUtils;

/**
 * Class that holds the advanced information needed for the
 * {@link org.dspace.xmlworkflow.state.actions.processingaction.ScoreReviewAction}
 * See config {@code workflow-actions.cfg}
 */
public class ScoreReviewActionAdvancedInfo extends ActionAdvancedInfo {

    private boolean descriptionRequired;

    // 🔹 Alterado de int → double para permitir valores decimais
    private double maxValue;

    public boolean isDescriptionRequired() {
        return descriptionRequired;
    }

    public void setDescriptionRequired(boolean descriptionRequired) {
        this.descriptionRequired = descriptionRequired;
    }

    public double getMaxValue() {
        return maxValue;
    }

    public void setMaxValue(double maxValue) {
        this.maxValue = maxValue;
    }

    @Override
    public void generateId(String type) {
        // 🔹 Inclui o valor decimal formatado corretamente (ex.: "10.0" ao invés de "10")
        String idString = type
            + ";descriptionRequired," + descriptionRequired
            + ";maxValue," + String.format("%.2f", maxValue);
        super.id = DigestUtils.md5DigestAsHex(idString.getBytes());
    }
}
