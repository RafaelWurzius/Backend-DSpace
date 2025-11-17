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
 * Holds advanced information needed for the
 * {@link org.dspace.xmlworkflow.state.actions.processingaction.SelectReviewerAction}.
 * 
 * Supports configuration for both reviewer group and advisor selection,
 * allowing the UI to know when an orientador (advisor) must be chosen.
 */
public class SelectReviewerActionAdvancedInfo extends ActionAdvancedInfo {

    /** Reviewer group identifier (UUID or name). */
    private String group;

    /** Optional: UUID or email of the advisor (orientador). */
    private String advisor;

    /** Indicates if the advisor selection is required in the UI. */
    private boolean advisorRequired = false;

    public String getGroup() {
        return group;
    }

    public void setGroup(String group) {
        this.group = group;
    }

    public String getAdvisor() {
        return advisor;
    }

    public void setAdvisor(String advisor) {
        this.advisor = advisor;
    }

    public boolean isAdvisorRequired() {
        return advisorRequired;
    }

    public void setAdvisorRequired(boolean advisorRequired) {
        this.advisorRequired = advisorRequired;
    }

    @Override
    public void generateId(String type) {
        String idString = type
            + ";group," + (group != null ? group : "none")
            + ";advisor," + (advisor != null ? advisor : "none")
            + ";advisorRequired," + advisorRequired;
        super.id = DigestUtils.md5DigestAsHex(idString.getBytes());
    }
}
