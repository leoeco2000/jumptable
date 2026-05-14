package org.springframework.boot.jumptable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jumptable.DtoTreeIndexBuilder.AssignmentSourceProperty;
import org.springframework.boot.jumptable.DtoTreeIndexBuilder.DtoIndexedTree;
import org.springframework.boot.jumptable.DtoTreeIndexBuilder.JavaEdge;
import org.springframework.boot.jumptable.DtoTreeIndexBuilder.JavaNode;
import org.springframework.boot.jumptable.DtoTreeIndexBuilder.Node;
import org.springframework.boot.jumptable.DtoTreeIndexBuilder.PartsDto;
import org.springframework.boot.jumptable.DtoTreeIndexBuilder.PositionAtClass;
import org.springframework.boot.jumptable.DtoTreeIndexBuilder.RangeMatch;

class DtoTreeIndexBuilderTests {

  @Test
  void buildsNestedDtoTreeAndFindsOverlappingSourceFields() {
    DtoTreeIndexBuilder builder = new DtoTreeIndexBuilder();

    List<JavaNode> javaNodeList = List.of(
        new JavaNode("class-root", "DtoClass", null, null, null, null, "RootDto"),
        new JavaNode("class-body", "DtoClass", null, null, null, null, "BodyDto"),
        new JavaNode("field-header", "DtoField", "header", "String", null, new PositionAtClass(0, 4), null),
        new JavaNode("field-body", "DtoField", "body", "Dto", new PartsDto("class-body", "BodyDto"),
            new PositionAtClass(4, 12), null),
        new JavaNode("field-tail", "DtoField", "tail", "String", null, new PositionAtClass(12, 16), null),
        new JavaNode("field-code", "DtoField", "code", "String", null, new PositionAtClass(0, 3), null),
        new JavaNode("field-desc", "DtoField", "desc", "String", null, new PositionAtClass(3, 8), null));

    List<JavaEdge> javaEdgeList = List.of(
        new JavaEdge("field-header", "class-root", "BELONG_TO"),
        new JavaEdge("field-body", "class-root", "BELONG_TO"),
        new JavaEdge("field-tail", "class-root", "BELONG_TO"),
        new JavaEdge("field-code", "class-body", "BELONG_TO"),
        new JavaEdge("field-desc", "class-body", "BELONG_TO"));

    DtoIndexedTree tree = builder.build(javaNodeList, javaEdgeList);

    Node root = tree.root();
    assertEquals("RootDto", root.fieldName());
    assertEquals("Dto", root.fieldType());
    assertEquals(0, root.startIndex());
    assertEquals(16, root.len());
    assertEquals(0, root.positionAtClass().startIndex());
    assertEquals(3, root.children().size());

    Node header = root.children().get(0);
    assertEquals("header", header.fieldName());
    assertEquals(0, header.startIndex());
    assertEquals(0, header.positionAtClass().startIndex());
    assertEquals(4, header.positionAtClass().endIndex());

    Node body = root.children().get(1);
    assertEquals("body", body.fieldName());
    assertEquals("Dto", body.fieldType());
    assertEquals(4, body.startIndex());
    assertEquals(8, body.len());
    assertEquals(4, body.positionAtClass().startIndex());
    assertEquals(12, body.positionAtClass().endIndex());
    assertEquals(2, body.children().size());

    Node code = body.children().get(0);
    assertEquals("code", code.fieldName());
    assertEquals(4, code.startIndex());
    assertEquals(0, code.positionAtClass().startIndex());
    assertEquals(3, code.positionAtClass().endIndex());

    assertIterableEquals(
        List.of("header", "code", "desc", "tail"),
        tree.orderedLeafNodes().stream().map(Node::fieldName).toList());

    List<RangeMatch> matches = tree.findSourceFields(2, 6);
    assertEquals(3, matches.size());
    assertEquals("header", matches.get(0).sourceNode().fieldName());
    assertEquals(2, matches.get(0).overlapLen());
    assertEquals(2, matches.get(0).sourceClassOverlapStart());
    assertEquals(2, matches.get(0).sourceClassOverlapLen());
    assertEquals(2, matches.get(0).sourceOffset());
    assertEquals(0, matches.get(0).targetOffset());

    assertEquals("code", matches.get(1).sourceNode().fieldName());
    assertEquals(3, matches.get(1).overlapLen());
    assertEquals(0, matches.get(1).sourceClassOverlapStart());
    assertEquals(3, matches.get(1).sourceClassOverlapLen());
    assertEquals(0, matches.get(1).sourceOffset());
    assertEquals(2, matches.get(1).targetOffset());

    assertEquals("desc", matches.get(2).sourceNode().fieldName());
    assertEquals(1, matches.get(2).overlapLen());
    assertEquals(3, matches.get(2).sourceClassOverlapStart());
    assertEquals(1, matches.get(2).sourceClassOverlapLen());
    assertEquals(0, matches.get(2).sourceOffset());
    assertEquals(5, matches.get(2).targetOffset());
  }

  @Test
  void plansSourceAssignmentWithTruncationAndConcatenation() {
    DtoTreeIndexBuilder builder = new DtoTreeIndexBuilder();

    JavaNode rootClass = new JavaNode("class-root", "DtoClass", null, null, null, null, "RootDto");
    JavaNode sourceClass = new JavaNode("class-source", "DtoClass", null, null, null, null, "SourceDto");
    JavaNode sourceField = new JavaNode("field-source", "DtoField", "source", "Dto",
        new PartsDto("class-source", "SourceDto"), new PositionAtClass(0, 8), null);
    JavaNode targetField = new JavaNode("field-target", "DtoField", "target", "String", null,
        new PositionAtClass(2, 8), null);
    JavaNode part1Field = new JavaNode("field-part1", "DtoField", "part1", "String", null,
        new PositionAtClass(0, 3), null);
    JavaNode part2Field = new JavaNode("field-part2", "DtoField", "part2", "String", null,
        new PositionAtClass(3, 8), null);
    List<JavaNode> javaNodeList = List.of(
        rootClass,
        sourceClass,
        sourceField,
        targetField,
        part1Field,
        part2Field);

    List<JavaEdge> javaEdgeList = List.of(
        new JavaEdge("field-source", "class-root", "BELONG_TO"),
        new JavaEdge("field-target", "class-root", "BELONG_TO"),
        new JavaEdge("field-part1", "class-source", "BELONG_TO"),
        new JavaEdge("field-part2", "class-source", "BELONG_TO"));

    DtoIndexedTree tree = builder.build(javaNodeList, javaEdgeList);

    List<AssignmentSourceProperty> sourceProperties = tree.listSourceAssignmentProperties("field-target", sourceClass);
    assertEquals(2, sourceProperties.size());

    AssignmentSourceProperty firstProperty = sourceProperties.get(0);
    assertEquals("field-part1", firstProperty.propertyUuid());
    assertEquals("part1", firstProperty.propertyName());
    assertEquals(false, firstProperty.targetInsufficient());
    assertEquals(0, firstProperty.targetMissingLen());
    assertEquals(false, firstProperty.sourceInsufficient());
    assertEquals(0, firstProperty.sourceMissingLen());
    assertEquals(true, firstProperty.needTruncate());
    assertEquals(2, firstProperty.truncateOffset());
    assertEquals(1, firstProperty.truncateLen());
    assertIterableEquals(
        List.of(
            Map.of("uuid", "class-root", "type", "DtoClass", "name", "RootDto", "javaNode", rootClass),
            Map.of("uuid", "field-source", "type", "DtoField", "name", "source", "javaNode", sourceField),
            Map.of("uuid", "class-source", "type", "DtoClass", "name", "SourceDto", "javaNode", sourceClass),
            Map.of("uuid", "field-part1", "type", "DtoField", "name", "part1", "javaNode", part1Field)),
        firstProperty.pathKeyValues());

    AssignmentSourceProperty secondProperty = sourceProperties.get(1);
    assertEquals("field-part2", secondProperty.propertyUuid());
    assertEquals("part2", secondProperty.propertyName());
    assertEquals(false, secondProperty.targetInsufficient());
    assertEquals(0, secondProperty.targetMissingLen());
    assertEquals(false, secondProperty.sourceInsufficient());
    assertEquals(0, secondProperty.sourceMissingLen());
    assertEquals(false, secondProperty.needTruncate());
    assertEquals(0, secondProperty.truncateOffset());
    assertEquals(0, secondProperty.truncateLen());
    assertIterableEquals(
        List.of(
            Map.of("uuid", "class-root", "type", "DtoClass", "name", "RootDto", "javaNode", rootClass),
            Map.of("uuid", "field-source", "type", "DtoField", "name", "source", "javaNode", sourceField),
            Map.of("uuid", "class-source", "type", "DtoClass", "name", "SourceDto", "javaNode", sourceClass),
            Map.of("uuid", "field-part2", "type", "DtoField", "name", "part2", "javaNode", part2Field)),
        secondProperty.pathKeyValues());

    List<AssignmentSourceProperty> propertiesByTargetAndSourceField =
        tree.listSourceAssignmentProperties(targetField, sourceField);
    assertEquals(2, propertiesByTargetAndSourceField.size());
    assertEquals("field-part1", propertiesByTargetAndSourceField.get(0).propertyUuid());
    assertEquals(true, propertiesByTargetAndSourceField.get(0).needTruncate());
    assertEquals("field-part2", propertiesByTargetAndSourceField.get(1).propertyUuid());
    assertEquals(false, propertiesByTargetAndSourceField.get(1).needTruncate());

    List<AssignmentSourceProperty> propertiesByTargetAndSourceClass =
        tree.listSourceAssignmentProperties(rootClass, sourceClass);
    assertEquals(2, propertiesByTargetAndSourceClass.size());
    assertEquals("field-part1", propertiesByTargetAndSourceClass.get(0).propertyUuid());
    assertEquals(false, propertiesByTargetAndSourceClass.get(0).needTruncate());
    assertEquals(0, propertiesByTargetAndSourceClass.get(0).truncateOffset());
    assertEquals(0, propertiesByTargetAndSourceClass.get(0).truncateLen());
    assertEquals("field-part2", propertiesByTargetAndSourceClass.get(1).propertyUuid());
    assertEquals(false, propertiesByTargetAndSourceClass.get(1).needTruncate());

    List<AssignmentSourceProperty> propertiesByTargetAndSourceUuid =
        tree.listSourceAssignmentProperties("field-target", "field-source");
    assertEquals(2, propertiesByTargetAndSourceUuid.size());
    assertEquals("field-part1", propertiesByTargetAndSourceUuid.get(0).propertyUuid());
    assertEquals(true, propertiesByTargetAndSourceUuid.get(0).needTruncate());
    assertEquals(2, propertiesByTargetAndSourceUuid.get(0).truncateOffset());
    assertEquals(1, propertiesByTargetAndSourceUuid.get(0).truncateLen());
    assertEquals("field-part2", propertiesByTargetAndSourceUuid.get(1).propertyUuid());
    assertEquals(false, propertiesByTargetAndSourceUuid.get(1).needTruncate());

    List<AssignmentSourceProperty> windowedProperties =
        tree.listSourceAssignmentProperties(targetField, 1, 10, sourceField, 2, 10);
    assertEquals(2, windowedProperties.size());
    assertEquals("field-part1", windowedProperties.get(0).propertyUuid());
    assertEquals(true, windowedProperties.get(0).targetInsufficient());
    assertEquals(5, windowedProperties.get(0).targetMissingLen());
    assertEquals(true, windowedProperties.get(0).sourceInsufficient());
    assertEquals(4, windowedProperties.get(0).sourceMissingLen());
    assertEquals(true, windowedProperties.get(0).needTruncate());
    assertEquals(2, windowedProperties.get(0).truncateOffset());
    assertEquals(1, windowedProperties.get(0).truncateLen());
    assertEquals("field-part2", windowedProperties.get(1).propertyUuid());
    assertEquals(true, windowedProperties.get(1).targetInsufficient());
    assertEquals(5, windowedProperties.get(1).targetMissingLen());
    assertEquals(true, windowedProperties.get(1).sourceInsufficient());
    assertEquals(4, windowedProperties.get(1).sourceMissingLen());
    assertEquals(true, windowedProperties.get(1).needTruncate());
    assertEquals(0, windowedProperties.get(1).truncateOffset());
    assertEquals(4, windowedProperties.get(1).truncateLen());

    List<AssignmentSourceProperty> windowedPropertiesByUuid =
        tree.listSourceAssignmentProperties("field-target", 1, 10, "field-source", 2, 10);
    assertEquals(2, windowedPropertiesByUuid.size());
    assertEquals("field-part1", windowedPropertiesByUuid.get(0).propertyUuid());
    assertEquals(true, windowedPropertiesByUuid.get(0).needTruncate());
    assertEquals("field-part2", windowedPropertiesByUuid.get(1).propertyUuid());
    assertEquals(true, windowedPropertiesByUuid.get(1).needTruncate());
  }

  @Test
  void supportsInclusiveEndIndexMode() {
    DtoTreeIndexBuilder builder = new DtoTreeIndexBuilder(DtoTreeIndexBuilder.RangeMode.END_INCLUSIVE);

    List<JavaNode> javaNodeList = List.of(
        new JavaNode("class-root", "DtoClass", null, null, null, null, "RootDto"),
        new JavaNode("field-name", "DtoField", "name", "String", null, new PositionAtClass(0, 2), null));

    List<JavaEdge> javaEdgeList = List.of(new JavaEdge("field-name", "class-root", "BELONG_TO"));

    DtoIndexedTree tree = builder.build(javaNodeList, javaEdgeList);
    assertEquals(3, tree.root().len());
    assertEquals(3, tree.orderedLeafNodes().get(0).len());
  }
}
