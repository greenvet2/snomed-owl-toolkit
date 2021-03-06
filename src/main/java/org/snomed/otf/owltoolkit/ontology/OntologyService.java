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
package org.snomed.otf.owltoolkit.ontology;

import com.google.common.base.Strings;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.FunctionalSyntaxDocumentFormat;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.util.DefaultPrefixManager;
import org.snomed.otf.owltoolkit.constants.Concepts;
import org.snomed.otf.owltoolkit.domain.AxiomRepresentation;
import org.snomed.otf.owltoolkit.domain.Relationship;
import org.snomed.otf.owltoolkit.ontology.render.SnomedFunctionalSyntaxDocumentFormat;
import org.snomed.otf.owltoolkit.ontology.render.SnomedFunctionalSyntaxStorerFactory;
import org.snomed.otf.owltoolkit.ontology.render.SnomedPrefixManager;
import org.snomed.otf.owltoolkit.service.ReasonerServiceException;
import org.snomed.otf.owltoolkit.service.ReasonerServiceRuntimeException;
import org.snomed.otf.owltoolkit.taxonomy.SnomedTaxonomy;
import uk.ac.manchester.cs.owl.owlapi.OWLDataFactoryImpl;

import java.io.OutputStream;
import java.util.*;

import static java.lang.Long.parseLong;

@SuppressWarnings("Guava")
public class OntologyService {

	public static final String SNOMED_CORE_COMPONENTS_URI = "http://snomed.info/id/";
	public static final String SNOMED_INTERNATIONAL_EDITION_URI = "http://snomed.info/sct/900000000000207008";
	public static final String ONTOLOGY_URI_VERSION_POSTFIX = "/version/";
	public static final String COLON = ":";
	public static final String ROLE_GROUP_SCTID = "609096000";
	public static final String ROLE_GROUP_OUTDATED_CONSTANT = "roleGroup";
	public static final String SNOMED_ROLE_GROUP_SHORT_URI = COLON + ROLE_GROUP_SCTID;
	public static final String SNOMED_ROLE_GROUP_FULL_URI = SNOMED_CORE_COMPONENTS_URI + ROLE_GROUP_SCTID;

	public static final String CORE_COMPONENT_NAMESPACE_PATTERN = "<http://snomed.info/id/([0-9]+)>";

	private final OWLOntologyManager manager;
	private OWLDataFactory factory;
	private DefaultPrefixManager prefixManager;
	private final Set<Long> ungroupedAttributes;

	public OntologyService(Set<Long> ungroupedAttributes) {
		this.ungroupedAttributes = ungroupedAttributes;
		manager = OWLManager.createOWLOntologyManager();
		factory = new OWLDataFactoryImpl();
		prefixManager = new DefaultPrefixManager();
		prefixManager.setDefaultPrefix(SNOMED_CORE_COMPONENTS_URI);
	}

	public OWLOntology createOntology(SnomedTaxonomy snomedTaxonomy) throws OWLOntologyCreationException {
		return createOntology(snomedTaxonomy, null, null);
	}

	public OWLOntology createOntology(SnomedTaxonomy snomedTaxonomy, String ontologyUri, String versionDate) throws OWLOntologyCreationException {

		Set<OWLAxiom> axioms = new HashSet<>();

		// Create Axioms of Snomed attributes
		Set<Long> attributeConceptIds = snomedTaxonomy.getAttributeConceptIds();
		for (Long attributeConceptId : attributeConceptIds) {
			OWLObjectProperty owlObjectProperty = getOwlObjectProperty(attributeConceptId);
			for (Relationship relationship : snomedTaxonomy.getStatedRelationships(attributeConceptId)) {
				if (relationship.getTypeId() == Concepts.IS_A_LONG && relationship.getDestinationId() != Concepts.CONCEPT_MODEL_ATTRIBUTE_LONG) {
					axioms.add(factory.getOWLSubObjectPropertyOfAxiom(owlObjectProperty, getOwlObjectProperty(relationship.getDestinationId())));
				}
			}
			addFSNAnnotation(attributeConceptId, snomedTaxonomy, axioms);
		}

		// Create Axioms of all other Snomed concepts
		for (Long conceptId : snomedTaxonomy.getAllConceptIds()) {

			boolean primitive = snomedTaxonomy.isPrimitive(conceptId);
			Collection<Relationship> statedRelationships = snomedTaxonomy.getStatedRelationships(conceptId);

			AxiomRepresentation representation = new AxiomRepresentation();
			representation.setPrimitive(primitive);
			representation.setLeftHandSideNamedConcept(conceptId);
			Map<Integer, List<Relationship>> relationshipMap = new HashMap<>();
			for (Relationship statedRelationship : statedRelationships) {
				relationshipMap.computeIfAbsent(statedRelationship.getGroup(), g -> new ArrayList<>()).add(statedRelationship);
			}
			representation.setRightHandSideRelationships(relationshipMap);
			OWLClassAxiom conceptAxiom = createOwlClassAxiom(representation);
			axioms.add(conceptAxiom);

			// Add raw axioms from the axiom reference set file
			Set<OWLAxiom> conceptAxioms = snomedTaxonomy.getConceptAxiomMap().get(conceptId);
			if (conceptAxioms != null) {
				axioms.addAll(conceptAxioms);
			}

			addFSNAnnotation(conceptId, snomedTaxonomy, axioms);
		}

		OWLOntology ontology;
		if (Strings.isNullOrEmpty(ontologyUri)) {
			ontologyUri = SNOMED_INTERNATIONAL_EDITION_URI;
		}
		if (Strings.isNullOrEmpty(versionDate)) {
			ontology = manager.createOntology(IRI.create(ontologyUri));
		} else {
			ontology = manager.createOntology(new OWLOntologyID(
					com.google.common.base.Optional.of(IRI.create(ontologyUri)),
					com.google.common.base.Optional.of(IRI.create(ontologyUri + ONTOLOGY_URI_VERSION_POSTFIX + versionDate))));
		}

		manager.addAxioms(ontology, axioms);
		return ontology;
	}

	public void saveOntology(OWLOntology ontology, OutputStream outputStream) throws OWLOntologyStorageException {
		manager.getOntologyStorers().add(new SnomedFunctionalSyntaxStorerFactory());

		FunctionalSyntaxDocumentFormat owlDocumentFormat = new SnomedFunctionalSyntaxDocumentFormat();
		owlDocumentFormat.setPrefixManager(new SnomedPrefixManager());
		owlDocumentFormat.setDefaultPrefix("http://snomed.info/id/");
		ontology.getOWLOntologyManager().setOntologyFormat(ontology, owlDocumentFormat);
		ontology.saveOntology(owlDocumentFormat, outputStream);
	}

	public OWLClassAxiom createOwlClassAxiom(AxiomRepresentation axiomRepresentation) {
		// Left side is usually a single named concept
		OWLClassExpression leftSide = createOwlClassExpression(axiomRepresentation.getLeftHandSideNamedConcept(), axiomRepresentation.getLeftHandSideRelationships());

		// Right side is usually an expression created from a set of stated relationships
		OWLClassExpression rightSide = createOwlClassExpression(axiomRepresentation.getRightHandSideNamedConcept(), axiomRepresentation.getRightHandSideRelationships());

		if (axiomRepresentation.isPrimitive()) {
			return factory.getOWLSubClassOfAxiom(leftSide, rightSide);
		} else {
			return factory.getOWLEquivalentClassesAxiom(leftSide, rightSide);
		}
	}

	private OWLClassExpression createOwlClassExpression(Long namedConcept, Map<Integer, List<Relationship>> relationships) {
		if (namedConcept != null) {
			return getOwlClass(namedConcept);
		}

		// Process all concept's relationships
		final Set<OWLClassExpression> terms = new HashSet<>();
		Map<Integer, Set<OWLClassExpression>> nonZeroRoleGroups = new TreeMap<>();
		for (List<Relationship> relationshipList : relationships.values()) {
			for (Relationship relationship : relationshipList) {
				int group = relationship.getGroup();
				long typeId = relationship.getTypeId();
				long destinationId = relationship.getDestinationId();
				if (typeId == Concepts.IS_A_LONG) {
					terms.add(getOwlClass(destinationId));
				} else if (group == 0) {
					if (ungroupedAttributes.contains(typeId)) {
						// Special cases
						terms.add(getOwlObjectSomeValuesFrom(typeId, destinationId));
					} else {
						// Self grouped relationships in group 0
						terms.add(getOwlObjectSomeValuesFromGroup(getOwlObjectSomeValuesFrom(typeId, destinationId)));
					}
				} else {
					// Collect statements in the same role group into sets
					nonZeroRoleGroups.computeIfAbsent(group, g -> new HashSet<>())
							.add(getOwlObjectSomeValuesFrom(typeId, destinationId));
				}
			}
		}

		// For each role group if there is more than one statement in the group we wrap them in an ObjectIntersectionOf statement
		for (Integer group : nonZeroRoleGroups.keySet()) {
			Set<OWLClassExpression> expressionGroup = nonZeroRoleGroups.get(group);
			// Write out a group of expressions
			terms.add(getOwlObjectSomeValuesFromGroup(getOnlyValueOrIntersection(expressionGroup)));
		}

		if (terms.isEmpty()) {
			// SNOMED CT root concept
			terms.add(factory.getOWLThing());
		}

		return getOnlyValueOrIntersection(terms);
	}

	public Set<PropertyChain> getPropertyChains(OWLOntology owlOntology) {
		Set<PropertyChain> propertyChains = new HashSet<>();

		// Collect property chain axioms
		for (OWLSubPropertyChainOfAxiom propertyChainAxiom : owlOntology.getAxioms(AxiomType.SUB_PROPERTY_CHAIN_OF)) {
			List<OWLObjectPropertyExpression> propertyChain = propertyChainAxiom.getPropertyChain();
			assertTrue("Property chain must be 2 properties long.", propertyChain.size() == 2);
			Long sourceType = getShortForm(propertyChain.get(0));
			Long destinationType = getShortForm(propertyChain.get(1));
			OWLObjectPropertyExpression superProperty = propertyChainAxiom.getSuperProperty();
			Long inferredType = getShortForm(superProperty);
			propertyChains.add(new PropertyChain(sourceType, destinationType, inferredType));
		}

		// Build property chains from transitive properties
		for (OWLTransitiveObjectPropertyAxiom transitiveObjectPropertyAxiom : owlOntology.getAxioms(AxiomType.TRANSITIVE_OBJECT_PROPERTY)) {
			Long propertyId = getShortForm(transitiveObjectPropertyAxiom.getProperty());
			propertyChains.add(new PropertyChain(propertyId, propertyId, propertyId));
		}

		return propertyChains;
	}

	private Long getShortForm(OWLObjectPropertyExpression property) {
		String shortForm = property.getNamedProperty().getIRI().getShortForm();
		return parseLong(shortForm);
	}

	private OWLClassExpression getOnlyValueOrIntersection(Set<OWLClassExpression> terms) {
		return terms.size() == 1 ? terms.iterator().next() : factory.getOWLObjectIntersectionOf(terms);
	}

	private OWLObjectSomeValuesFrom getOwlObjectSomeValuesFromGroup(OWLClassExpression owlObjectSomeValuesFrom) {
		return getOwlObjectSomeValuesWithPrefix(SNOMED_ROLE_GROUP_SHORT_URI, owlObjectSomeValuesFrom);
	}

	private OWLObjectSomeValuesFrom getOwlObjectSomeValuesWithPrefix(String prefix, OWLClassExpression owlObjectSomeValuesFrom) {
		return factory.getOWLObjectSomeValuesFrom(factory.getOWLObjectProperty(prefix, prefixManager), owlObjectSomeValuesFrom);
	}

	private OWLObjectSomeValuesFrom getOwlObjectSomeValuesFrom(long typeId, long destinationId) {
		return factory.getOWLObjectSomeValuesFrom(getOwlObjectProperty(typeId), getOwlClass(destinationId));
	}

	private OWLObjectProperty getOwlObjectProperty(long typeId) {
		return factory.getOWLObjectProperty(COLON + typeId, prefixManager);
	}

	private OWLClass getOwlClass(Long conceptId) {
		return factory.getOWLClass(COLON + conceptId, prefixManager);
	}

	private void addFSNAnnotation(Long conceptId, SnomedTaxonomy snomedTaxonomy, Set<OWLAxiom> axioms) {
		String conceptFsnTerm = snomedTaxonomy.getConceptFsnTerm(conceptId);
		if (conceptFsnTerm != null) {
			axioms.add(factory.getOWLAnnotationAssertionAxiom(factory.getRDFSLabel(), IRI.create(SNOMED_CORE_COMPONENTS_URI + conceptId), factory.getOWLLiteral(conceptFsnTerm)));
		}
	}

	public DefaultPrefixManager getPrefixManager() {
		return prefixManager;
	}

	private void assertTrue(String message, boolean bool) {
		if (!bool) {
			throw new ReasonerServiceRuntimeException(message);
		}
	}
}
