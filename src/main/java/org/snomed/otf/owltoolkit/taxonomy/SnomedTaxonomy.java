/*
 * Copyright 2017 SNOMED International, http://snomed.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.snomed.otf.owltoolkit.taxonomy;

import com.google.common.collect.Sets;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snomed.otf.owltoolkit.constants.Concepts;
import org.snomed.otf.owltoolkit.domain.Relationship;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

import static java.lang.Long.parseLong;

public class SnomedTaxonomy {

	private Set<Long> allConceptIds = new LongOpenHashSet();
	private Set<Long> fullyDefinedConceptIds = new LongOpenHashSet();
	private Map<Long, Relationship> statedRelationshipsById = new HashMap<>();
	private Map<Long, Relationship> inferredRelationshipsById = new HashMap<>();
	private Map<String, OWLAxiom> axiomsById = new HashMap<>();
	private Map<Long, Set<Relationship>> conceptStatedRelationshipMap = new Long2ObjectOpenHashMap<>();
	private Map<Long, Set<Relationship>> conceptInferredRelationshipMap = new Long2ObjectOpenHashMap<>();
	private Map<Long, Set<OWLAxiom>> conceptAxiomMap = new Long2ObjectOpenHashMap<>();
	private Map<Long, Set<Long>> statedSubTypesMap = new Long2ObjectOpenHashMap<>();
	private Map<Long, Set<Long>> ungroupedRolesByContentType = new HashMap<>();
	private Set<Long> inactivatedConcepts = new LongOpenHashSet();
	private Map<Long, String> conceptFsnTermMap = new Long2ObjectOpenHashMap<>();

	public static final Set<Long> DEFAULT_NEVER_GROUPED_ROLE_IDS = Collections.unmodifiableSet(Sets.newHashSet(
			parseLong(Concepts.PART_OF),
			parseLong(Concepts.LATERALITY),
			parseLong(Concepts.HAS_ACTIVE_INGREDIENT),
			parseLong(Concepts.HAS_DOSE_FORM)
	));

	private static final Logger LOGGER = LoggerFactory.getLogger(SnomedTaxonomy.class);

	public boolean isPrimitive(Long conceptId) {
		return !fullyDefinedConceptIds.contains(conceptId);
	}

	/**
	 * Returns with all the active source relationships of a concept given by its unique ID.
	 * @param conceptId the ID of the SNOMED&nbsp;CT concept.
	 * @return the active source relationships.
	 */
	public Collection<Relationship> getStatedRelationships(Long conceptId) {
		return conceptStatedRelationshipMap.getOrDefault(conceptId, Collections.emptySet());
	}
	
	public Collection<Relationship> getInferredRelationships(Long conceptId) {
		return conceptInferredRelationshipMap.getOrDefault(conceptId, Collections.emptySet());
	}

	public void addOrModifyRelationship(boolean stated, long conceptId, Relationship relationship) {
		// Have we seen this relationship before ie we need to modify it?
		Relationship existingRelationship = stated ? statedRelationshipsById.get(relationship.getRelationshipId())
				: inferredRelationshipsById.get(relationship.getRelationshipId());

		if (existingRelationship != null) {
			// Only effectiveTime and groupId are mutable
			existingRelationship.setEffectiveTime(relationship.getEffectiveTime());
			existingRelationship.setGroup(relationship.getGroup());
		} else {
			// add relationship
			if (stated) {
				conceptStatedRelationshipMap.computeIfAbsent(conceptId, k -> new HashSet<>()).add(relationship);
				if (relationship.getTypeId() == Concepts.IS_A_LONG) {
					statedSubTypesMap.computeIfAbsent(relationship.getDestinationId(), k -> new HashSet<>()).add(conceptId);
				}
				statedRelationshipsById.put(relationship.getRelationshipId(), relationship);
			} else {
				conceptInferredRelationshipMap.computeIfAbsent(conceptId, k -> new HashSet<>()).add(relationship);
				inferredRelationshipsById.put(relationship.getRelationshipId(), relationship);
			}
		}
	}

	// TODO: Replace this - just collect the attributes used in active relationships
	public Set<Long> getAttributeConceptIds() {
		Set<Long> attributeIds = new HashSet<>();
		for (Long conceptId : allConceptIds) {
			if (conceptHasAncestor(conceptId, Concepts.CONCEPT_MODEL_ATTRIBUTE_LONG)) {
				attributeIds.add(conceptId);
			}
		}
		return attributeIds;
	}
	
	public boolean conceptHasAncestor(long conceptId, long ancestor) {
		return conceptHasAncestor(conceptId, ancestor, 0);
	}

	public boolean conceptHasAncestor(long conceptId, long ancestor, long depth) {
		if (conceptId == Concepts.ROOT_LONG) {
			return false;
		}
		
		//TODO Temporary code to find out why we're seeing a recursive hierarchy
		if (depth > 30) {
			throw new RuntimeException("Depth limit exceeded searching for potential ancestor " + ancestor + " of concept " + conceptId ); 
		}

		// Check all ancestors for the attribute concept
		for (Relationship relationship : conceptStatedRelationshipMap.getOrDefault(conceptId, Collections.emptySet())) {
			if (relationship.getTypeId() == Concepts.IS_A_LONG) {
				return relationship.getDestinationId() == ancestor || conceptHasAncestor(relationship.getDestinationId(), ancestor, ++depth);
			}
		}
		return false;
	}

	public Set<Long> getSuperTypeIds(long conceptId) {
		if (conceptId == Concepts.ROOT_LONG) {
			return Collections.emptySet();
		}

		Set<Long> superTypes = new HashSet<>();
		for (Relationship relationship : conceptStatedRelationshipMap.get(conceptId)) {
			if (relationship.getTypeId() == Concepts.IS_A_LONG) {
				superTypes.add(relationship.getDestinationId());
			}
		}
		return superTypes;
	}

	public Collection<Relationship> getNonIsAStatements(Long conceptId) {
		return conceptStatedRelationshipMap.getOrDefault(conceptId, Collections.emptySet()).stream().filter(f -> f.getTypeId() != Concepts.IS_A_LONG).collect(Collectors.toList());
	}

	public Set<Long> getSubTypeIds(long conceptId) {
		Set<Long> longs = statedSubTypesMap.get(conceptId);
		return longs != null ? longs : Collections.emptySet();
	}

	public Set<Long> getDescendants(long conceptId) {
		return getDescendants(conceptId, new LongOpenHashSet());
	}

	private Set<Long> getDescendants(long conceptId, Set<Long> ids) {
		Set<Long> subTypeIds = getSubTypeIds(conceptId);
		ids.addAll(subTypeIds);
		for (Long subTypeId : subTypeIds) {
			getDescendants(subTypeId, ids);
		}
		return ids;
	}

	public boolean isExhaustive(long conceptId) {
		// TODO: is this always false?
		return false;
	}

	public Set<Long> getAllConceptIds() {
		return allConceptIds;
	}

	public Set<Long> getFullyDefinedConceptIds() {
		return fullyDefinedConceptIds;
	}

	public Set<Long> getConceptIdSet() {
		return allConceptIds;
	}

	public Collection<Relationship> getInferredRelationships(long conceptId) {
		return conceptInferredRelationshipMap.getOrDefault(conceptId, Collections.emptySet());
	}

	public void removeRelationship(boolean stated, String sourceId, String relationshipIdStr) {
		long relationshipId = parseLong(relationshipIdStr);
		if (stated) {
			getStatedRelationships(parseLong(sourceId)).removeIf(relationship -> relationshipId == relationship.getRelationshipId());
			statedRelationshipsById.remove(relationshipId);
		} else {
			getInferredRelationships(parseLong(sourceId)).removeIf(relationship -> relationshipId == relationship.getRelationshipId());
			inferredRelationshipsById.remove(relationshipId);
		}
	}

	public void addAxiom(String referencedComponentId, String axiomId, OWLAxiom owlAxiom) {
		conceptAxiomMap.computeIfAbsent(parseLong(referencedComponentId), id -> new HashSet<>()).add(owlAxiom);
		axiomsById.put(axiomId, owlAxiom);
	}

	public void removeAxiom(String referencedComponentId, String id) {
		// Find the previously loaded axiom by id so that it can be removed from the set of axioms on the concept
		OWLAxiom owlAxiomToRemove = axiomsById.remove(id);
		if (owlAxiomToRemove != null) {
			conceptAxiomMap.get(parseLong(referencedComponentId)).remove(owlAxiomToRemove);
		}
	}

	public void addFsn(String conceptId, String term) {
		conceptFsnTermMap.put(parseLong(conceptId), term);
	}

	public String getConceptFsnTerm(Long conceptId) {
		return conceptFsnTermMap.get(conceptId);
	}

	public void addUngroupedRole(Long contentType, Long attributeId) {
		ungroupedRolesByContentType.computeIfAbsent(contentType, type -> new HashSet<>()).add(attributeId);
	}

	public void removeUngroupedRole(Long contentType, Long attributeId) {
		Set<Long> ungrouped = ungroupedRolesByContentType.get(contentType);
		if (ungrouped != null) {
			ungrouped.remove(attributeId);
		}
	}

	public Map<Long, Set<Long>> getUngroupedRolesByContentType() {
		return ungroupedRolesByContentType;
	}

	public Set<Long> getUngroupedRolesForContentTypeOrDefault(Long contentTypeId) {
		Set<Long> subTypeIds = getSubTypeIds(contentTypeId);
		Set<Long> ungrouped = new HashSet<>();
		for (Long subTypeId : subTypeIds) {
			ungrouped.addAll(ungroupedRolesByContentType.getOrDefault(subTypeId, new HashSet<>()));
		}
		ungrouped.addAll(ungroupedRolesByContentType.getOrDefault(contentTypeId, new HashSet<>()));

		if (!ungrouped.isEmpty()) {
			LOGGER.info("Using never grouped role list from MRCM reference set {}", ungrouped);
		} else {
			ungrouped = SnomedTaxonomy.DEFAULT_NEVER_GROUPED_ROLE_IDS;
			LOGGER.info("No MRCM information found, falling back to legacy never grouped role list {}", ungrouped);
		}
		return ungrouped;
	}

	public Map<Long, Set<OWLAxiom>> getConceptAxiomMap() {
		return conceptAxiomMap;
	}

	private void saveRelationshipsToDisk(Map<Long, Set<Relationship>> relationshipMap, File outputFile) throws IOException {
		String TSV_FIELD_DELIMITER = "\t";
		String LINE_DELIMITER = "\r\n";
		try(	OutputStreamWriter osw = new OutputStreamWriter(new FileOutputStream(outputFile, true), StandardCharsets.UTF_8);
				BufferedWriter bw = new BufferedWriter(osw);
				PrintWriter out = new PrintWriter(bw))
		{
			String header = "id\teffectiveTime\tactive\tmoduleId\tsourceId\tdestinationId\trelationshipGroup\ttypeId\tcharacteristicTypeId\tmodifierId";
			out.print(header + LINE_DELIMITER);

			StringBuilder line = new StringBuilder();
			for (Map.Entry<Long, Set<Relationship>> fragSetEntry : relationshipMap.entrySet()) {

				for (Relationship relationship : fragSetEntry.getValue()) {
					line.setLength(0);
					line.append(relationship.getRelationshipId()).append(TSV_FIELD_DELIMITER)
							.append(relationship.getEffectiveTime()).append(TSV_FIELD_DELIMITER)
							.append("1").append(TSV_FIELD_DELIMITER)
							.append(relationship.getModuleId()).append(TSV_FIELD_DELIMITER)
							.append(fragSetEntry.getKey()).append(TSV_FIELD_DELIMITER)
							.append(relationship.getDestinationId()).append(TSV_FIELD_DELIMITER)
							.append(relationship.getGroup()).append(TSV_FIELD_DELIMITER)
							.append(relationship.getTypeId()).append(TSV_FIELD_DELIMITER)
							.append(relationship.getCharacteristicTypeId()).append(TSV_FIELD_DELIMITER)
							.append(Concepts.EXISTENTIAL_RESTRICTION_MODIFIER);
					out.print(line.toString() + LINE_DELIMITER);
				}
			}
		}
	}

	public Set<Long> getInactivatedConcepts() {
		return inactivatedConcepts;
	}
}
