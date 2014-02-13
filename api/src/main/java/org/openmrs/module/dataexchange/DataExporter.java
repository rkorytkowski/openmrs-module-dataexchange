/**
 * The contents of this file are subject to the OpenMRS Public License
 * Version 1.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://license.openmrs.org
 *
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations
 * under the License.
 *
 * Copyright (C) OpenMRS, LLC.  All Rights Reserved.
 */
package org.openmrs.module.dataexchange;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.commons.io.IOUtils;
import org.dbunit.DatabaseUnitException;
import org.dbunit.database.DatabaseConnection;
import org.dbunit.dataset.CompositeDataSet;
import org.dbunit.dataset.DataSetException;
import org.dbunit.dataset.ITable;
import org.dbunit.dataset.ReplacementTable;
import org.dbunit.dataset.xml.FlatXmlDataSet;
import org.hibernate.SessionFactory;
import org.openmrs.module.dataexchange.Table.Reference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DataExporter {
	
	@Autowired
	SessionFactory sessionFactory;

	@Transactional
	public void exportConcepts(String filePath, Set<Integer> ids) throws IOException {
		DatabaseConnection connection = getConnection();
		
		Table concept = buildConceptTable();
		
		List<ITable> tables = new ArrayList<ITable>();
		
		try {
			addTable(connection, tables, concept, concept.getPrimaryKey(), ids);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		
		OutputStreamWriter writer = null;
		try {
			writer = new OutputStreamWriter(new FileOutputStream(filePath), "UTF-8");
			
			FlatXmlDataSet.write(new CompositeDataSet(tables.toArray(new ITable[tables.size()])), writer);
			
			writer.close();
		} catch (DataSetException e) {
			throw new RuntimeException(e);
		} finally {
			IOUtils.closeQuietly(writer);
		}
	}
	
	@SuppressWarnings("deprecation")
	private DatabaseConnection getConnection() {
		try {
			return new DatabaseConnection(sessionFactory.getCurrentSession().connection());
		} catch (DatabaseUnitException e) {
			throw new RuntimeException(e);
		}
	}

	private Table buildConceptTable() {
		Table conceptDatatype = new Table.Builder("concept_datatype").build();
		Table conceptClass = new Table.Builder("concept_class").build();
		Table concept = new Table.Builder("concept").addFK("datatype_id", conceptDatatype)
				.addFK("class_id", conceptClass).build();
		
		new Table.Builder("concept_name").addReferenceAndFK("concept_id", concept).build();
		new Table.Builder("concept_description").addReferenceAndFK("concept_id", concept).build();
		
		new Table.Builder("concept_set").addReferenceAndFK("concept_id", concept).build();
		
		Table drug = new Table.Builder("drug").addReferenceAndFK("concept_id", concept).addFK("dosage_form", concept)
				.addFK("route", concept).build();
		
		new Table.Builder("concept_answer").addReferenceAndFK("concept_id", concept)
				.addFK("answer_concept", concept).addFK("answer_drug", drug).build();
		
		Table conceptSource = new Table.Builder("concept_reference_source").addPK("concept_source_id").build();
		
		Table conceptReferenceTerm = new Table.Builder("concept_reference_term")
				.addFK("concept_source_id", conceptSource).build();
		
		Table conceptMapType = new Table.Builder("concept_map_type").build();
		
		new Table.Builder("concept_reference_term_map")
			.addReferenceAndFK("term_a_id", conceptReferenceTerm).addReferenceAndFK("term_b_id", conceptReferenceTerm)
			.addFK("a_is_to_b_id", conceptMapType).build();
		
		new Table.Builder("concept_reference_map").addPK("concept_map_id")
				.addFK("concept_map_type_id", conceptMapType).addReferenceAndFK("concept_id", concept)
				.addFK("concept_reference_term_id", conceptReferenceTerm).build();
		return concept;
	}

	private void addTable(DatabaseConnection connection,
			List<ITable> tables, Table table, String key, Set<Integer> ids) throws SQLException, DataSetException {
		StringBuilder selectConcept = new StringBuilder("select * from " + table.getName() + " where " + key + " in (?");
		for (int i = 1; i < ids.size(); i++) {
			selectConcept.append(", ?");
		}
		selectConcept.append(")");
		
		PreparedStatement selectConceptQuery = connection.getConnection().prepareStatement(selectConcept.toString());
		
		int index = 1;
		for (Integer id: ids) {
			selectConceptQuery.setInt(index, id);
			index++;
		}
		
		ITable resultTable = connection.createTable(table.getName(), selectConceptQuery);
		
		if (resultTable.getRowCount() == 0) {
			return;
		}

		Set<Integer> notFetchedIds = new HashSet<Integer>();
		
		for (int i = 0; i < resultTable.getRowCount(); i++) {
			Integer id = (Integer) resultTable.getValue(i, table.getPrimaryKey());
			if (table.addFetchedId(id)) {
				notFetchedIds.add(id);
			}
		}
		
		for (Entry<String, Table> fk : table.getForeignKeys().entrySet()) {
			Set<Integer> notFetchedForeignIds = new HashSet<Integer>();
			
			for (int i = 0; i < resultTable.getRowCount(); i++) {
				Integer id = (Integer) resultTable.getValue(i, table.getPrimaryKey());
				if (!notFetchedIds.contains(id)) {
					continue;
				}
				
				Integer fkValue = (Integer) resultTable.getValue(i, fk.getKey());
				if (!fk.getValue().getFetchedIds().contains(fkValue)) {
					notFetchedForeignIds.add(fkValue);
				}
			}
			
			if (!notFetchedForeignIds.isEmpty()) {
				addTable(connection, tables, fk.getValue(), fk.getValue().getPrimaryKey(), notFetchedForeignIds);
			}
		}
		
		ReplacementTable replacementTable = new ReplacementTable(resultTable);
		replacementTable.addReplacementObject("creator", 1);
		replacementTable.addReplacementObject("retired_by", 1);
		replacementTable.addReplacementObject("changed_by", 1);
		tables.add(replacementTable);
		
		if (!notFetchedIds.isEmpty()) {
			for (Reference reference : table.getReferences()) {
				addTable(connection, tables, reference.getTable(), reference.getForeingKey(), notFetchedIds);
			}
		}
	}
}