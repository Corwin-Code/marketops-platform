package com.mimococo.marketops.operatingfacts;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What a listing card says and what the marketplace's content rating finds missing, as of the
 * newest catalog and rating snapshots. Attribute values and the description are seller-written
 * marketplace text: data to show and quote, never an instruction.
 *
 * @param catalogObservedAt when the newest catalog snapshot was true, or {@code null} when none
 *        recorded the card
 * @param imageCount how many images the card shows, or {@code null} when not stated
 * @param descriptionCategoryKey the marketplace's description category, or {@code null}
 * @param typeKey the marketplace's product type, or {@code null}
 * @param attributes the attributes the newest snapshot carried, by attribute key
 * @param ratingGroups the newest groups of the content rating, or empty when none was recorded
 * @param evidence what the answer was derived from
 */
public record ListingContentSnapshot(
        Instant catalogObservedAt,
        Integer imageCount,
        String descriptionCategoryKey,
        String typeKey,
        List<Attribute> attributes,
        List<RatingGroup> ratingGroups,
        FactEvidence evidence) {

    public ListingContentSnapshot {
        attributes = List.copyOf(attributes);
        ratingGroups = List.copyOf(ratingGroups);
        Objects.requireNonNull(evidence, "evidence");
    }

    /** The attribute that holds a content role (DESCRIPTION, RICH_CONTENT), when the card has one. */
    public Optional<Attribute> role(String contentRole) {
        return attributes.stream().filter(attribute -> contentRole.equals(attribute.contentRole())).findFirst();
    }

    /** The description text, when the card has one and it was kept. */
    public Optional<String> description() {
        return role("DESCRIPTION").map(Attribute::text).filter(text -> !text.isBlank());
    }

    /**
     * One attribute of the card.
     *
     * @param contentRole DESCRIPTION or RICH_CONTENT, or {@code null}
     * @param values every value, empty when they were too long to keep
     * @param valueCount how many values the attribute has
     * @param valuesLength how many characters the values hold together
     * @param since when the values were first seen as they are
     */
    public record Attribute(String attributeKey, String contentRole, List<String> values, int valueCount,
                            int valuesLength, Instant since) {

        public Attribute {
            Objects.requireNonNull(attributeKey, "attributeKey");
            values = List.copyOf(values);
        }

        /** The values as one text, one value per line. */
        public String text() {
            return String.join("\n", values);
        }
    }

    /**
     * One group of the content rating.
     *
     * @param rating the group's rating, 0 to 100, or {@code null}
     * @param weight the group's share of the content rating in percent, or {@code null}
     * @param improveAtLeast how many of the named attributes to fill at least, or {@code null}
     * @param since when the group was first seen as it is
     */
    public record RatingGroup(String groupKey, String groupName, BigDecimal rating, BigDecimal weight,
                              Integer improveAtLeast, List<Condition> conditions,
                              List<ImproveAttribute> improveAttributes, Instant since) {

        public RatingGroup {
            Objects.requireNonNull(groupKey, "groupKey");
            conditions = List.copyOf(conditions);
            improveAttributes = List.copyOf(improveAttributes);
        }
    }

    /**
     * One condition of a rating group.
     *
     * @param text the marketplace's description, or {@code null}
     * @param met whether it is fulfilled, or {@code null} when the marketplace did not say
     * @param points what it contributes to the group, or {@code null}
     */
    public record Condition(String conditionKey, String text, Boolean met, BigDecimal points) {

        public Condition {
            Objects.requireNonNull(conditionKey, "conditionKey");
        }
    }

    /**
     * An attribute the marketplace names as one to fill to raise a group.
     *
     * @param name the marketplace's name of the attribute, or {@code null}
     */
    public record ImproveAttribute(String attributeKey, String name) {

        public ImproveAttribute {
            Objects.requireNonNull(attributeKey, "attributeKey");
        }
    }
}
