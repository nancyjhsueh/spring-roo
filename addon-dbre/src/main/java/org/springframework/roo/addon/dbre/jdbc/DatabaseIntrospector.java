package org.springframework.roo.addon.dbre.jdbc;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.roo.addon.dbre.model.CascadeAction;
import org.springframework.roo.addon.dbre.model.Column;
import org.springframework.roo.addon.dbre.model.ColumnType;
import org.springframework.roo.addon.dbre.model.Database;
import org.springframework.roo.addon.dbre.model.ForeignKey;
import org.springframework.roo.addon.dbre.model.Index;
import org.springframework.roo.addon.dbre.model.IndexColumn;
import org.springframework.roo.addon.dbre.model.Reference;
import org.springframework.roo.addon.dbre.model.Schema;
import org.springframework.roo.addon.dbre.model.Sequence;
import org.springframework.roo.addon.dbre.model.Table;
import org.springframework.roo.addon.dbre.model.TableType;
import org.springframework.roo.addon.dbre.model.dialect.Dialect;
import org.springframework.roo.support.util.Assert;
import org.springframework.roo.support.util.StringUtils;

/**
 * Creates a {@link Database database} model from a live database using JDBC.
 * 
 * @author Alan Stewart
 * @since 1.1
 */
public class DatabaseIntrospector {
	private static final String[] TYPES = { TableType.TABLE.name() };
	private Connection connection;
	private DatabaseMetaData databaseMetaData;
	private String catalogName;
	private Schema schema;
	private Set<String> excludeTables;
	private String tableName;
	private String columnName;
	private String[] types = TYPES;

	public DatabaseIntrospector(Connection connection, Schema schema, Set<String> excludeTables) throws SQLException {
		Assert.notNull(connection, "Connection must not be null");
		this.connection = connection;
		catalogName = this.connection.getCatalog();
		databaseMetaData = this.connection.getMetaData();
		Assert.notNull(databaseMetaData, "Database metadata is null");
		this.schema = schema;
		this.excludeTables = excludeTables;
	}

	public DatabaseIntrospector(Connection connection) throws SQLException {
		this(connection, null, null);
	}

	public Connection getConnection() {
		return connection;
	}

	public String getCatalogName() {
		return catalogName;
	}

	public void setCatalogName(String catalogName) {
		this.catalogName = catalogName;
	}

	public Schema getSchema() {
		return schema;
	}

	public String getSchemaName() {
		return schema != null ? schema.getName() : null;
	}

	public void setSchema(Schema schema) {
		this.schema = schema;
	}

	public String getTableName() {
		return tableName;
	}

	public void setTableName(String tableName) {
		this.tableName = tableName;
	}

	public String getColumnName() {
		return columnName;
	}

	public void setColumnName(String columnName) {
		this.columnName = columnName;
	}

	public String[] getTypes() {
		return types;
	}

	public void setTypes(String[] types) {
		this.types = types;
	}

	public Set<Schema> getSchemas() throws SQLException {
		Set<Schema> schemas = new LinkedHashSet<Schema>();

		ResultSet rs = databaseMetaData.getSchemas();
		try {
			while (rs.next()) {
				schemas.add(new Schema(rs.getString("TABLE_SCHEM")));
			}
		} finally {
			rs.close();
		}

		return schemas;
	}

	public Database getDatabase() throws SQLException {
		Database database = new Database(getCatalog(), schema, readTables());
		database.setSequences(readSequences());
		database.setExcludeTables(excludeTables);
		return database;
	}

	private Set<Table> readTables() throws SQLException {
		Set<Table> tables = new LinkedHashSet<Table>();

		ResultSet rs = databaseMetaData.getTables(getCatalog(), getSchemaPattern(), getTableNamePattern(), types);
		try {
			while (rs.next()) {
				tableName = rs.getString("TABLE_NAME");
				catalogName = rs.getString("TABLE_CAT");
				String schemaName = rs.getString("TABLE_SCHEM");
				schema = new Schema(!StringUtils.hasText(schemaName) && StringUtils.hasText(catalogName) ? catalogName : schemaName);

				// Check for certain tables such as Oracle recycle bin tables, and ignore
				if (ignoreTables()) {
					continue;
				}

				if (!hasExcludedTable(tableName)) {
					Table table = new Table();
					table.setName(tableName);
					table.setCatalog(catalogName);
					table.setSchema(schema);
					table.setDescription(rs.getString("REMARKS"));

					table.addColumns(readColumns());
					table.addForeignKeys(readImportedKeys());
					table.addExportedKeys(readExportedKeys());
					table.addIndices(readIndices());

					for (String columnName : readPrimaryKeyNames()) {
						Column column = table.findColumn(columnName);
						if (column != null) {
							column.setPrimaryKey(true);
						}
					}

					tables.add(table);
				}
			}
		} finally {
			rs.close();
		}

		return tables;
	}

	private boolean ignoreTables() {
		boolean ignore = false;
		try {
			if ("Oracle".equalsIgnoreCase(databaseMetaData.getDatabaseProductName()) && tableName.startsWith("BIN$")) {
				ignore = true;
			}
		} catch (SQLException ignored) {
		}
		return ignore;
	}

	private Set<Column> readColumns() throws SQLException {
		Set<Column> columns = new LinkedHashSet<Column>();

		ResultSet rs = databaseMetaData.getColumns(getCatalog(), getSchemaPattern(), getTableNamePattern(), getColumnNamePattern());
		try {
			while (rs.next()) {
				Column column = new Column(rs.getString("COLUMN_NAME"));
				column.setDescription(rs.getString("REMARKS"));
				column.setDefaultValue(rs.getString("COLUMN_DEF"));
				column.setTypeCode(rs.getInt("DATA_TYPE"));
				column.setType(ColumnType.getColumnType(column.getTypeCode())); // "TYPE_NAME"
				
				int columnSize = rs.getInt("COLUMN_SIZE");
				switch (column.getType()) {
					case DECIMAL:
					case DOUBLE:
					case NUMERIC:
						column.setPrecision(columnSize);
						column.setScale(rs.getInt("DECIMAL_DIGITS"));
						column.setLength(0);
						break;
					case CHAR:
						if (columnSize > 1) {
							column.setType(ColumnType.VARCHAR);
							column.setLength(columnSize);
						}
						break;
					default:
						column.setLength(columnSize);
						break;
				}
				
				column.setRequired("NO".equalsIgnoreCase(rs.getString("IS_NULLABLE")));
				column.setOrdinalPosition(rs.getInt("ORDINAL_POSITION"));

				columns.add(column);
			}
		} finally {
			rs.close();
		}

		return columns;
	}

	private Set<ForeignKey> readImportedKeys() throws SQLException {
		Map<String, ForeignKey> foreignKeys = new LinkedHashMap<String, ForeignKey>();

		ResultSet rs = databaseMetaData.getImportedKeys(getCatalog(), getSchemaPattern(), getTableNamePattern());
		try {
			while (rs.next()) {
				String name = rs.getString("FK_NAME");
				String foreignTableName = rs.getString("PKTABLE_NAME");
				String key = name + "_" + foreignTableName;

				if (!hasExcludedTable(foreignTableName)) {
					ForeignKey foreignKey = new ForeignKey(name, foreignTableName);
					foreignKey.setOnUpdate(getCascadeAction(rs.getShort("UPDATE_RULE")));
					foreignKey.setOnDelete(getCascadeAction(rs.getShort("DELETE_RULE")));

					Reference reference = new Reference();
					reference.setSequenceNumber(rs.getShort("KEY_SEQ"));
					reference.setLocalColumnName(rs.getString("FKCOLUMN_NAME"));
					reference.setForeignColumnName(rs.getString("PKCOLUMN_NAME"));

					if (foreignKeys.containsKey(key)) {
						foreignKeys.get(key).addReference(reference);
					} else {
						foreignKey.addReference(reference);
						foreignKeys.put(key, foreignKey);
					}
				}
			}
		} finally {
			rs.close();
		}

		return new LinkedHashSet<ForeignKey>(foreignKeys.values());
	}

	private CascadeAction getCascadeAction(Short actionValue) {
		CascadeAction cascadeAction;
		switch (actionValue.intValue()) {
			case DatabaseMetaData.importedKeyCascade:
				cascadeAction = CascadeAction.CASCADE;
				break;
			case DatabaseMetaData.importedKeySetNull:
				cascadeAction = CascadeAction.SET_NULL;
				break;
			case DatabaseMetaData.importedKeySetDefault:
				cascadeAction = CascadeAction.SET_DEFAULT;
				break;
			case DatabaseMetaData.importedKeyRestrict:
				cascadeAction = CascadeAction.RESTRICT;
				break;
			case DatabaseMetaData.importedKeyNoAction:
				cascadeAction = CascadeAction.NONE;
				break;
			default:
				cascadeAction = CascadeAction.NONE;
		}
		return cascadeAction;
	}

	private Set<ForeignKey> readExportedKeys() throws SQLException {
		Map<String, ForeignKey> exportedKeys = new LinkedHashMap<String, ForeignKey>();

		ResultSet rs = databaseMetaData.getExportedKeys(getCatalog(), getSchemaPattern(), getTableNamePattern());
		try {
			while (rs.next()) {
				String name = rs.getString("FK_NAME");
				String foreignTableName = rs.getString("FKTABLE_NAME");
				String key = name + "_" + foreignTableName;

				if (!hasExcludedTable(foreignTableName)) {
					ForeignKey foreignKey = new ForeignKey(name, foreignTableName);
					foreignKey.setOnUpdate(getCascadeAction(rs.getShort("UPDATE_RULE")));
					foreignKey.setOnDelete(getCascadeAction(rs.getShort("DELETE_RULE")));

					Reference reference = new Reference();
					reference.setSequenceNumber(rs.getShort("KEY_SEQ"));
					reference.setLocalColumnName(rs.getString("PKCOLUMN_NAME"));
					reference.setForeignColumnName(rs.getString("FKCOLUMN_NAME"));

					if (exportedKeys.containsKey(key)) {
						exportedKeys.get(key).addReference(reference);
					} else {
						foreignKey.addReference(reference);
						exportedKeys.put(key, foreignKey);
					}
				}
			}
		} finally {
			rs.close();
		}

		return new LinkedHashSet<ForeignKey>(exportedKeys.values());
	}

	private boolean hasExcludedTable(String tableName) {
		if (excludeTables != null && StringUtils.hasText(tableName)) {
			for (String excludedTable : excludeTables) {
				String regex = excludedTable.replaceAll("\\*", ".*").replaceAll("\\?", ".?");
				Pattern pattern = Pattern.compile(regex);
				if (pattern.matcher(tableName).matches()) {
					return true;
				}
			}
		}
		return false;
	}

	private Set<Index> readIndices() throws SQLException {
		Set<Index> indices = new LinkedHashSet<Index>();

		ResultSet rs;
		try {
			// Catching SQLException here due to Oracle throwing exception when attempting to retrieve indices for deleted tables that exist in Oracle's recycle bin
			rs = databaseMetaData.getIndexInfo(catalogName, getSchemaPattern(), tableName, false, false);
		} catch (SQLException e) {
			return indices;
		}

		if (rs != null) {
			try {
				while (rs.next()) {
					Short type = rs.getShort("TYPE");
					if (type == DatabaseMetaData.tableIndexStatistic) {
						continue;
					}

					String indexName = rs.getString("INDEX_NAME");
					Index index = findIndex(indexName, indices);
					if (index == null) {
						index = new Index(indexName);
					} else {
						indices.remove(index);
					}
					index.setUnique(!rs.getBoolean("NON_UNIQUE"));

					IndexColumn indexColumn = new IndexColumn(rs.getString("COLUMN_NAME"));
					indexColumn.setOrdinalPosition(rs.getShort("ORDINAL_POSITION"));

					index.addColumn(indexColumn);

					indices.add(index);
				}
			} finally {
				rs.close();
			}
		}

		return indices;
	}

	private Index findIndex(String name, Set<Index> indices) {
		for (Index index : indices) {
			if (index.getName().equalsIgnoreCase(name)) {
				return index;
			}
		}
		return null;
	}

	private Set<String> readPrimaryKeyNames() throws SQLException {
		Set<String> columnNames = new LinkedHashSet<String>();

		ResultSet rs = databaseMetaData.getPrimaryKeys(catalogName, getSchemaPattern(), tableName);
		try {
			while (rs.next()) {
				columnNames.add(rs.getString("COLUMN_NAME"));
			}
		} finally {
			rs.close();
		}

		return columnNames;
	}

	private Set<Sequence> readSequences() {
		Set<Sequence> sequences = new LinkedHashSet<Sequence>();
		Dialect dialect = getDialect();
		if (dialect != null && dialect.supportsSequences()) {
			PreparedStatement pstmt = null;
			ResultSet rs = null;
			try {
				pstmt = connection.prepareStatement(dialect.getQuerySequencesString(schema));
				rs = pstmt.executeQuery();
				while (rs.next()) {
					sequences.add(new Sequence(rs.getString(1)));
				}
			} catch (SQLException ignored) {} 
			finally {
				if (rs != null) {
					try {
						rs.close();
					} catch (SQLException ignored) {}
				}
				if (pstmt != null) {
					try {
						pstmt.close();
					} catch (SQLException ignored) {}
				}
			}
		}
		return sequences;
	}
	
	
	private String getCatalog() throws SQLException {
		if (databaseMetaData.storesLowerCaseIdentifiers()) {
			return StringUtils.toLowerCase(catalogName);
		} else if (databaseMetaData.storesUpperCaseIdentifiers()) {
			return StringUtils.toUpperCase(catalogName);
		} else {
			return catalogName;
		}
		
	}
	private String getSchemaPattern() throws SQLException {
		if (databaseMetaData.storesLowerCaseIdentifiers()) {
			return StringUtils.toLowerCase(getSchemaName());
		} else if (databaseMetaData.storesUpperCaseIdentifiers()) {
			return StringUtils.toUpperCase(getSchemaName());
		} else {
			return getSchemaName();
		}
	}

	private String getTableNamePattern() throws SQLException {
		if (databaseMetaData.storesLowerCaseIdentifiers()) {
			return StringUtils.toLowerCase(tableName);
		} else if (databaseMetaData.storesUpperCaseIdentifiers()) {
			return StringUtils.toUpperCase(tableName);
		} else {
			return tableName;
		}
	}
	
	private String getColumnNamePattern() throws SQLException {
		if (databaseMetaData.storesLowerCaseIdentifiers()) {
			return StringUtils.toLowerCase(columnName);
		} else if (databaseMetaData.storesUpperCaseIdentifiers()) {
			return StringUtils.toUpperCase(columnName);
		} else {
			return columnName;
		}
	}

	private Dialect getDialect() {
		try {
			String productName = databaseMetaData.getDatabaseProductName();
			return (Dialect) Class.forName("org.springframework.roo.addon.dbre.model.dialect." + productName + "Dialect").newInstance();
		} catch (Exception e) {
			return null;
		}
	}
}