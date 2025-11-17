/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.model;

/**
 * The SelectReviewerActionAdvancedInfoRest REST Resource,
 * see {@link org.dspace.xmlworkflow.state.actions.processingaction.SelectReviewerActionAdvancedInfo}
 */
public class SelectReviewerActionAdvancedInfoRest extends AdvancedInfoRest {

    private String groupId;

    // 🆕 Novo campo indicando se a seleção de orientador é necessária
    private boolean advisorRequired;

    public String getGroup() {
        return groupId;
    }

    public void setGroup(String groupId) {
        this.groupId = groupId;
    }

    // 🆕 Getter e Setter para advisorRequired
    public boolean isAdvisorRequired() {
        return advisorRequired;
    }

    public void setAdvisorRequired(boolean advisorRequired) {
        this.advisorRequired = advisorRequired;
    }

    @Override
    public String getType() {
        return "selectrevieweractionadvancedinfo";
    }
}
