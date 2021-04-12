package org.odpi.openmetadata.adapters.connectors.integration.postgres;

/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright Contributors to the ODPi Egeria project. */

import org.odpi.openmetadata.accessservices.datamanager.metadataelements.DatabaseColumnElement;
import org.odpi.openmetadata.accessservices.datamanager.metadataelements.DatabaseElement;
import org.odpi.openmetadata.accessservices.datamanager.metadataelements.DatabaseSchemaElement;
import org.odpi.openmetadata.accessservices.datamanager.metadataelements.DatabaseTableElement;
import org.odpi.openmetadata.accessservices.datamanager.properties.*;
import org.odpi.openmetadata.adapters.connectors.integration.postgres.ffdc.AlreadyHandledException;
import org.odpi.openmetadata.adapters.connectors.integration.postgres.ffdc.ExceptionHandler;
import org.odpi.openmetadata.adapters.connectors.integration.postgres.ffdc.PostgresConnectorAuditCode;
import org.odpi.openmetadata.adapters.connectors.integration.postgres.ffdc.PostgresConnectorErrorCode;
import org.odpi.openmetadata.adapters.connectors.integration.postgres.mapper.PostgresMapper;
import org.odpi.openmetadata.adapters.connectors.integration.postgres.properties.PostgresColumn;
import org.odpi.openmetadata.adapters.connectors.integration.postgres.properties.PostgresDatabase;
import org.odpi.openmetadata.adapters.connectors.integration.postgres.properties.PostgresForeginKeyLinks;
import org.odpi.openmetadata.adapters.connectors.integration.postgres.properties.PostgresSchema;
import org.odpi.openmetadata.adapters.connectors.integration.postgres.properties.PostgresTable;
import org.odpi.openmetadata.frameworks.connectors.ffdc.ConnectorCheckedException;
import org.odpi.openmetadata.frameworks.connectors.ffdc.InvalidParameterException;
import org.odpi.openmetadata.frameworks.connectors.ffdc.PropertyServerException;
import org.odpi.openmetadata.frameworks.connectors.ffdc.UserNotAuthorizedException;
import org.odpi.openmetadata.integrationservices.database.connector.DatabaseIntegratorConnector;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class PostgresDatabaseConnector extends DatabaseIntegratorConnector
{

    @Override
    public void refresh() throws ConnectorCheckedException
    {

        String methodName = "PostgresConnector.refresh";
        int startFrom = 0;
        int pageSize = 100;

        PostgresSourceDatabase source = new PostgresSourceDatabase(connectionProperties);
        try
        {
            /*
            get a list of databases currently hosted in postgres
            and remove any databases that have been removed since the last refresh
             */
            List<PostgresDatabase> postgresDatabases = source.getDabases();
            List<DatabaseElement> egeriaDatabases = getContext().getMyDatabases(startFrom, pageSize);

            for (PostgresDatabase postgresDatabase : postgresDatabases)
            {
                boolean found = false;
                if (egeriaDatabases == null)
                {
                /*
                we have no databases in egeria
                so all databases are new OR
                there's been a failure and we are rebuilding
                 */
                    addDatabase(postgresDatabase);
                }
                else
                {
                    /*
                    check if the database is known to egeria
                    and needs to be updated
                     */
                    for (DatabaseElement egeriaDatabase : egeriaDatabases)
                    {

                        String egeriaQN =  egeriaDatabase.getDatabaseProperties().getQualifiedName();
                        String postgressQN = postgresDatabase.getQualifiedName();

                        if (egeriaQN.equals(postgressQN))
                        {
                        /*
                        we have found an exact instance to update
                         */
                            found = true;
                            updateDatabase(postgresDatabase, egeriaDatabase);
                            break;
                        }
                    }
                    /*
                    this is a new database so add it
                     */
                    if (!found)
                    {
                        addDatabase(postgresDatabase);
                    }
                }
            }
        }
        catch (SQLException error)
        {
            if (this.auditLog != null)
            {
                auditLog.logException(methodName,
                        PostgresConnectorAuditCode.ERROR_READING_DATABASES.getMessageDefinition(),
                        error);
            }

            throw new ConnectorCheckedException(PostgresConnectorErrorCode.ERROR_READING_DATABASES.getMessageDefinition(error.getClass().getName(),
                    error.getMessage()),
                    this.getClass().getName(),
                    methodName, error);

        }
        catch (InvalidParameterException error)
        {
            if (this.auditLog != null)
            {
                auditLog.logException(methodName,
                        PostgresConnectorAuditCode.INVALID_PARAMETER.getMessageDefinition(methodName),
                        error);
            }

            throw new ConnectorCheckedException(PostgresConnectorErrorCode.INVALID_PARAMETER.getMessageDefinition(error.getClass().getName(),
                    error.getMessage()),
                    this.getClass().getName(),
                    methodName, error);

        }
        catch (PropertyServerException error)
        {
            if (this.auditLog != null)
            {
                auditLog.logException(methodName,
                        PostgresConnectorAuditCode.PROPERTY_SERVER_EXCEPTION.getMessageDefinition(),
                        error);
            }

            throw new ConnectorCheckedException(PostgresConnectorErrorCode.PROPERTY_SERVER_EXCEPTION.getMessageDefinition(error.getClass().getName(),
                    error.getMessage()),
                    this.getClass().getName(),
                    methodName, error);

        }
        catch (UserNotAuthorizedException error)
        {
            if (this.auditLog != null)
            {
                auditLog.logException(methodName,
                        PostgresConnectorAuditCode.USER_NOT_AUTORIZED.getMessageDefinition(),
                        error);
            }

            throw new ConnectorCheckedException(PostgresConnectorErrorCode.USER_NOT_AUTHORIZED.getMessageDefinition(error.getClass().getName(),
                    error.getMessage()),
                    this.getClass().getName(),
                    methodName, error);

        }
        catch (ConnectorCheckedException error)
        {
            if (this.auditLog != null)
            {
                auditLog.logException(methodName,
                        PostgresConnectorAuditCode.CONNECTOR_CHECKED.getMessageDefinition( methodName ),
                        error);
            }

            throw error;
        }
        catch (AlreadyHandledException error)
        {
            throw new ConnectorCheckedException(PostgresConnectorErrorCode.ALREADY_HANDLED_EXCEPTION.getMessageDefinition(error.getClass().getName(),
                    error.getMessage()),
                    this.getClass().getName(),
                    methodName, error);

        } catch (Exception error)
        {
            if (this.auditLog != null)
            {
                auditLog.logException(methodName,
                        PostgresConnectorAuditCode.UNEXPECTTED_ERROR.getMessageDefinition(),
                        error);
            }

            throw new ConnectorCheckedException(PostgresConnectorErrorCode.UNEXPECTED_ERROR.getMessageDefinition(error.getClass().getName(),
                    error.getMessage()),
                    this.getClass().getName(),
                    methodName, error);

        }

    }

    /**
     * Checks if any databases need to be removed from egeria
     *
     * @param dbs            a list of the bean properties of a Postgres Database
     * @param knownDatabases a list of the Databases already known to egeria
     * @throws AlreadyHandledException
     */
    private void deleteDatabases(List<PostgresDatabase> dbs, List<DatabaseElement> knownDatabases) throws AlreadyHandledException
    {
        String methodName = "deleteDatabases";

        try
        {
            if (knownDatabases != null)
            {
                /*
                for each datbase already known to egeria
                 */
                for (DatabaseElement element : knownDatabases)
                {
                    String knownName = element.getDatabaseProperties().getQualifiedName();
                    /*
                    check that the database is still present in postgres
                     */
                    for (PostgresDatabase db : dbs)
                    {
                        String sourceName = db.getQualifiedName();
                        if (sourceName.equals(knownName))
                        {
                            /*
                            if found then check the next databsee
                             */
                            break;
                        }
                        /*
                        not found in postgres , so delete the datase from egeria
                         */
                        getContext().removeDatabase(element.getElementHeader().getGUID(), knownName);
                    }
                }
            }
        } catch (InvalidParameterException error)
        {
            ExceptionHandler.handleException(auditLog,
                    this.getClass().getName(),
                    methodName, error,
                    PostgresConnectorAuditCode.INVALID_PARAMETER.getMessageDefinition(methodName),
                    PostgresConnectorErrorCode.INVALID_PARAMETER.getMessageDefinition(error.getClass().getName()));
        } catch (PropertyServerException error)
        {
            ExceptionHandler.handleException(auditLog,
                    this.getClass().getName(),
                    methodName, error,
                    PostgresConnectorAuditCode.PROPERTY_SERVER_EXCEPTION.getMessageDefinition(),
                    PostgresConnectorErrorCode.PROPERTY_SERVER_EXCEPTION.getMessageDefinition(error.getClass().getName()));
        } catch (UserNotAuthorizedException error)
        {
            ExceptionHandler.handleException(auditLog,
                    this.getClass().getName(),
                    methodName, error,
                    PostgresConnectorAuditCode.USER_NOT_AUTORIZED.getMessageDefinition(),
                    PostgresConnectorErrorCode.USER_NOT_AUTHORIZED.getMessageDefinition(error.getClass().getName()));

        } catch (ConnectorCheckedException error)
        {
            ExceptionHandler.handleException(auditLog,
                    this.getClass().getName(),
                    methodName, error,
                    PostgresConnectorAuditCode.CONNECTOR_CHECKED.getMessageDefinition(methodName),
                    PostgresConnectorErrorCode.CONNECTOR_CHECKED.getMessageDefinition(error.getClass().getName()));
        }
    }

    /**
     * Trawls through a database updating a database where necessary
     *
     * @param postgresDatabase the bean properties of a Postgres Database
     * @param egeriaDatabase   the egeria database
     * @throws AlreadyHandledException
     */
    private void updateDatabase(PostgresDatabase postgresDatabase, DatabaseElement egeriaDatabase) throws AlreadyHandledException
    {
        String methodName = "updateDatabase";

        try
        {
            if (egeriaDatabase != null)
            {
                String guid = egeriaDatabase.getElementHeader().getGUID();
                /*
                have the properties of the database entity changed
                 */
                if (!postgresDatabase.equals(egeriaDatabase))
                {
                    /*
                    then we need to update the entity properties
                     */
                    DatabaseProperties props = PostgresMapper.getDatabaseProperties(postgresDatabase);
                    getContext().updateDatabase(guid, props);

                }

                /*
                now trawl through the rest of the schema
                updating where necessary
                 */
                updateSchemas(guid, postgresDatabase.getName());
            }
        } catch (InvalidParameterException error)
        {
            ExceptionHandler.handleException(auditLog,
                    this.getClass().getName(),
                    methodName, error,
                    PostgresConnectorAuditCode.INVALID_PARAMETER.getMessageDefinition(methodName),
                    PostgresConnectorErrorCode.INVALID_PARAMETER.getMessageDefinition(error.getClass().getName()));

        } catch (UserNotAuthorizedException error)
        {
            ExceptionHandler.handleException(auditLog,
                    this.getClass().getName(),
                    methodName, error,
                    PostgresConnectorAuditCode.USER_NOT_AUTORIZED.getMessageDefinition(),
                    PostgresConnectorErrorCode.USER_NOT_AUTHORIZED.getMessageDefinition(error.getClass().getName()));

        } catch (PropertyServerException error)
        {
            ExceptionHandler.handleException(auditLog,
                    this.getClass().getName(),
                    methodName, error,
                    PostgresConnectorAuditCode.PROPERTY_SERVER_EXCEPTION.getMessageDefinition(),
                    PostgresConnectorErrorCode.PROPERTY_SERVER_EXCEPTION.getMessageDefinition(error.getClass().getName()));
        } catch (ConnectorCheckedException error)
        {
            ExceptionHandler.handleException(auditLog,
                    this.getClass().getName(),
                    methodName, error,
                    PostgresConnectorAuditCode.CONNECTOR_CHECKED.getMessageDefinition(methodName),
                    PostgresConnectorErrorCode.CONNECTOR_CHECKED.getMessageDefinition(error.getClass().getName()));
        }

    }

    /**
     * iterates over the database schemas updating where necessary
     *
     * @param databaseGUID   the egeria database
     * @throws AlreadyHandledException
     */
    private void updateSchemas(String databaseGUID, String name) throws AlreadyHandledException
    {
        String methodName = "updateSchemas";
        int startFrom = 0;
        int pageSize = 100;

        PostgresSourceDatabase source = new PostgresSourceDatabase(this.connectionProperties);

        try
        {
               /*
            get a list of databases schema currently hosted in postgres
            and remove any databases schemas that have been dropped since the last refresh
             */
            List<PostgresSchema> schemas = source.getDatabaseSchema(name);
            List<DatabaseSchemaElement> knownSchemas = getContext().getSchemasForDatabase(databaseGUID, startFrom, pageSize);

            for (PostgresSchema postgresSchema : schemas)
            {
                boolean found = false;
                /*
                we have no schemas in egeria
                so all schemas are new
                 */
                if (knownSchemas == null)
                {
                    addSchemas(name, databaseGUID);
                }
                else
                {
                    /*
                    check if the schema is known to egeria
                    and needs to be updated
                     */
                    for (DatabaseSchemaElement egeriaSchema : knownSchemas)
                    {
                        if (egeriaSchema.getDatabaseSchemaProperties().getQualifiedName().equals(postgresSchema.getQualifiedName()))
                        {
                        /*
                        we have found an exact instance to update
                         */
                            found = true;
                            updateSchema(postgresSchema, egeriaSchema);
                            break;
                        }
                    }
                    /*
                    this is a new database so add it
                     */
                    if (!found)
                    {
                        addSchema(postgresSchema, databaseGUID);
                    }
                }
            }
        }
        catch (SQLException error)
        {
            ExceptionHandler.handleException(auditLog,
                    this.getClass().getName(),
                    methodName, error,
                    PostgresConnectorAuditCode.ERROR_READING_SCHEMAS.getMessageDefinition(),
                    PostgresConnectorErrorCode.ERROR_READING_SCHEMAS.getMessageDefinition(error.getClass().getName()));

        }

        catch (ConnectorCheckedException error)
        {
            ExceptionHandler.handleException(auditLog,
                    this.getClass().getName(),
                    methodName, error,
                    PostgresConnectorAuditCode.CONNECTOR_CHECKED.getMessageDefinition(methodName),
                    PostgresConnectorErrorCode.CONNECTOR_CHECKED.getMessageDefinition(error.getClass().getName()));

        }

        catch (InvalidParameterException error)
        {
            ExceptionHandler.handleException(auditLog,
                    this.getClass().getName(),
                    methodName, error,
                    PostgresConnectorAuditCode.INVALID_PARAMETER.getMessageDefinition(methodName),
                    PostgresConnectorErrorCode.INVALID_PARAMETER.getMessageDefinition(error.getClass().getName()));

        }
        catch (UserNotAuthorizedException error)
        {
            ExceptionHandler.handleException(auditLog,
                    this.getClass().getName(),
                    methodName, error,
                    PostgresConnectorAuditCode.USER_NOT_AUTORIZED.getMessageDefinition(),
                    PostgresConnectorErrorCode.USER_NOT_AUTHORIZED.getMessageDefinition(error.getClass().getName()));

        }

        catch (PropertyServerException error)
        {
            ExceptionHandler.handleException(auditLog,
                    this.getClass().getName(),
                    methodName, error,
                    PostgresConnectorAuditCode.PROPERTY_SERVER_EXCEPTION.getMessageDefinition(),
                    PostgresConnectorErrorCode.PROPERTY_SERVER_EXCEPTION.getMessageDefinition(error.getClass().getName()));

        }
    }

    /**
     * Changes the properties of an egeria schema entity
     *
     * @param postgresSchema            the Postgres Schema properties
     * @param egeriaSchema          the egeria schema
     * @throws AlreadyHandledException
     */
    private void updateSchema( PostgresSchema postgresSchema, DatabaseSchemaElement egeriaSchema) throws AlreadyHandledException
    {
        String methodName = "updateSchema";

        System.out.println("****************  Updating Schema ********************");
        try
        {
            if (postgresSchema.getQualifiedName().equals(egeriaSchema.getDatabaseSchemaProperties().getQualifiedName()))
            {
                DatabaseSchemaProperties props = PostgresMapper.getSchemaProperties(postgresSchema);
                getContext().updateDatabaseSchema(egeriaSchema.getElementHeader().getGUID(), props);
            }
            updateTables(postgresSchema, egeriaSchema);
        } catch (InvalidParameterException error)
        {
            ExceptionHandler.handleException(auditLog,
                    this.getClass().getName(),
                    methodName, error,
                    PostgresConnectorAuditCode.INVALID_PARAMETER.getMessageDefinition(methodName),
                    PostgresConnectorErrorCode.INVALID_PARAMETER.getMessageDefinition(error.getClass().getName()));

        } catch (PropertyServerException error)
        {
            ExceptionHandler.handleException(auditLog,
                    this.getClass().getName(),
                    methodName, error,
                    PostgresConnectorAuditCode.PROPERTY_SERVER_EXCEPTION.getMessageDefinition(),
                    PostgresConnectorErrorCode.PROPERTY_SERVER_EXCEPTION.getMessageDefinition(error.getClass().getName()));

        } catch (UserNotAuthorizedException error)
        {
            ExceptionHandler.handleException(auditLog,
                    this.getClass().getName(),
                    methodName, error,
                    PostgresConnectorAuditCode.USER_NOT_AUTORIZED.getMessageDefinition(),
                    PostgresConnectorErrorCode.USER_NOT_AUTHORIZED.getMessageDefinition(error.getClass().getName()));
        } catch (ConnectorCheckedException error)
        {
            ExceptionHandler.handleException(auditLog,
                    this.getClass().getName(),
                    methodName, error,
                    PostgresConnectorAuditCode.CONNECTOR_CHECKED.getMessageDefinition(methodName),
                    PostgresConnectorErrorCode.CONNECTOR_CHECKED.getMessageDefinition(error.getClass().getName()));
        }
    }

    /**
     * @param postgresSchema the postgres schema bean
     * @param egeriaSchema   the egeria schema bean
     * @throws AlreadyHandledException
     */
    private void updateTables(PostgresSchema postgresSchema, DatabaseSchemaElement egeriaSchema) throws AlreadyHandledException
    {
        final String methodName = "updateTables";
        int startFrom = 0;
        int pageSize = 100;

        String schemaGuid = egeriaSchema.getElementHeader().getGUID();
        PostgresSourceDatabase source = new PostgresSourceDatabase(this.connectionProperties);

        try
        {
            /*
            get a list of databases tables currently hosted in postgres
            and remove any tables that have been dropped since the last refresh
             */
            List<PostgresTable> postgresTables = source.getTables(postgresSchema.getSchema_name());
            List<DatabaseTableElement> egeriaTables = getContext().getTablesForDatabaseSchema(schemaGuid, startFrom, pageSize);

            for (PostgresTable postgresTable : postgresTables)
            {
                boolean found = false;
                /*
                we have no tables in egeria
                so all tables are new
                 */
                if (egeriaTables == null)
                {
                    addTable(postgresTable, schemaGuid);
                }
                else
                {
                    /*
                    check if the database table is known to egeria
                    and needs to be updated
                     */
                    for (DatabaseTableElement egeriaTable : egeriaTables)
                    {
                        if (egeriaTable.getDatabaseTableProperties().getQualifiedName().equals(postgresTable.getQualifiedName()))
                        {
                        /*
                        we have found an exact instance to update
                         */
                            found = true;
                            updateTable(postgresTable, egeriaTable);
                            break;
                        }
                    }
                    /*
                    this is a new database so add it
                     */
                    if (!found)
                    {
                        addTable(postgresTable, schemaGuid);
                    }
                }
            }
        }
        catch (SQLException error)
        {
            ExceptionHandler.handleException(auditLog,
                    this.getClass().getName(),
                    methodName, error,
                    PostgresConnectorAuditCode.ERROR_READING_TABLES.getMessageDefinition(),
                    PostgresConnectorErrorCode.ERROR_READING_TABLES.getMessageDefinition(error.getClass().getName()));

        }
        catch (InvalidParameterException error)
        {
            ExceptionHandler.handleException(auditLog,
                    this.getClass().getName(),
                    methodName, error,
                    PostgresConnectorAuditCode.INVALID_PARAMETER.getMessageDefinition(methodName),
                    PostgresConnectorErrorCode.INVALID_PARAMETER.getMessageDefinition(error.getClass().getName()));
        }
        catch (UserNotAuthorizedException error)
        {
            ExceptionHandler.handleException(auditLog,
                    this.getClass().getName(),
                    methodName, error,
                    PostgresConnectorAuditCode.USER_NOT_AUTORIZED.getMessageDefinition(),
                    PostgresConnectorErrorCode.USER_NOT_AUTHORIZED.getMessageDefinition(error.getClass().getName()));
        }
        catch (PropertyServerException error)
        {
            ExceptionHandler.handleException(auditLog,
                    this.getClass().getName(),
                    methodName, error,
                    PostgresConnectorAuditCode.PROPERTY_SERVER_EXCEPTION.getMessageDefinition(),
                    PostgresConnectorErrorCode.PROPERTY_SERVER_EXCEPTION.getMessageDefinition(error.getClass().getName()));
        }

        catch (ConnectorCheckedException error)
        {
            ExceptionHandler.handleException(auditLog,
                    this.getClass().getName(),
                    methodName, error,
                    PostgresConnectorAuditCode.CONNECTOR_CHECKED.getMessageDefinition(methodName),
                    PostgresConnectorErrorCode.CONNECTOR_CHECKED.getMessageDefinition(error.getClass().getName()));
        }
    }

    /**
     * @param postgresTable         the postgres table attributes to be added
     * @param egeriaTable    te GUID of the schema to which the table will be linked
     * @throws AlreadyHandledException
     */
    private void updateTable(PostgresTable postgresTable, DatabaseTableElement egeriaTable) throws AlreadyHandledException
    {
        String methodName = "updateTable";
        int startFrom = 0;
        int pageSize = 100;

        try
        {
            if( postgresTable.getQualifiedName().equals( egeriaTable.getDatabaseTableProperties().getQualifiedName()))
            {
                DatabaseTableProperties props = PostgresMapper.getTableProperties(postgresTable);
                getContext().updateDatabaseTable(egeriaTable.getElementHeader().getGUID(), props);
            }

            updateColumns(postgresTable, egeriaTable);
            //updatePrimaryKeys();
        }
        catch (InvalidParameterException error)
        {
            ExceptionHandler.handleException(auditLog,
                    this.getClass().getName(),
                    methodName, error,
                    PostgresConnectorAuditCode.INVALID_PARAMETER.getMessageDefinition(methodName),
                    PostgresConnectorErrorCode.INVALID_PARAMETER.getMessageDefinition(error.getClass().getName()));

        }
        catch (PropertyServerException error)
        {
            ExceptionHandler.handleException(auditLog,
                    this.getClass().getName(),
                    methodName, error,
                    PostgresConnectorAuditCode.PROPERTY_SERVER_EXCEPTION.getMessageDefinition(),
                    PostgresConnectorErrorCode.PROPERTY_SERVER_EXCEPTION.getMessageDefinition(error.getClass().getName()));

        }
        catch (UserNotAuthorizedException error)
        {
            ExceptionHandler.handleException(auditLog,
                    this.getClass().getName(),
                    methodName, error,
                    PostgresConnectorAuditCode.USER_NOT_AUTORIZED.getMessageDefinition(),
                    PostgresConnectorErrorCode.USER_NOT_AUTHORIZED.getMessageDefinition(error.getClass().getName()));

        }
        catch (ConnectorCheckedException error)
        {
            ExceptionHandler.handleException(auditLog,
                    this.getClass().getName(),
                    methodName, error,
                    PostgresConnectorAuditCode.CONNECTOR_CHECKED.getMessageDefinition(methodName),
                    PostgresConnectorErrorCode.CONNECTOR_CHECKED.getMessageDefinition(error.getClass().getName()));
        }

    }

    /**
     * @param table         the postgres table which contains the columns to be updates
     * @param  egeriaTable  the column data from egeria
     * @throws AlreadyHandledException
     */
    private void updateColumns(PostgresTable postgresTable, DatabaseTableElement egeriaTable) throws AlreadyHandledException
    {
        final String methodName = "updateColumns";
        int startFrom = 0;
        int pageSize = 100;

        PostgresSourceDatabase source = new PostgresSourceDatabase(this.connectionProperties);
        String guid = egeriaTable.getElementHeader().getGUID();
        try
        {
            List<PostgresColumn> postgresColumns = source.getColumns(postgresTable.getTable_name());
            List<DatabaseColumnElement> egeriaColumns = getContext().getColumnsForDatabaseTable(egeriaTable.getElementHeader().getGUID(), startFrom, pageSize);

            if( egeriaColumns != null )
            {
                deleteColumns(postgresColumns, egeriaColumns);


                for (PostgresColumn postgresColumn : postgresColumns)
                {
                    boolean found = false;
                    /*
                    we have no tables in egeria
                    so all tables are new
                     */
                    if (egeriaColumns == null)
                    {
                        addColumn(postgresColumn, guid);
                    }
                    else
                    {
                        /*
                        check if the database table is known to egeria
                        and needs to be updated
                         */
                        for (DatabaseColumnElement egeriaColumn : egeriaColumns)
                        {
                            if (egeriaColumn.getDatabaseColumnProperties().getQualifiedName().equals(postgresColumn.getQualifiedName()))
                            {
                            /*
                            we have found an exact instance to update
                             */
                                found = true;
                                updateColumn(postgresColumn, egeriaColumn);
                                break;
                            }
                        }
                        /*
                        this is a new database so add it
                         */
                        if (!found)
                        {
                            addColumn(postgresColumn, guid);
                        }
                    }
                }
            }
        }
        catch (SQLException error)
        {
            ExceptionHandler.handleException(auditLog,
                    this.getClass().getName(),
                    methodName, error,
                    PostgresConnectorAuditCode.ERROR_READING_COLUMNS.getMessageDefinition(),
                    PostgresConnectorErrorCode.ERROR_READING_COLUMNS.getMessageDefinition(error.getClass().getName()));

        }
        catch (InvalidParameterException error)
        {
            ExceptionHandler.handleException(auditLog,
                    this.getClass().getName(),
                    methodName, error,
                    PostgresConnectorAuditCode.INVALID_PARAMETER.getMessageDefinition(methodName),
                    PostgresConnectorErrorCode.INVALID_PARAMETER.getMessageDefinition(error.getClass().getName()));

        }
        catch (UserNotAuthorizedException error)
        {
            ExceptionHandler.handleException(auditLog,
                    this.getClass().getName(),
                    methodName, error,
                    PostgresConnectorAuditCode.USER_NOT_AUTORIZED.getMessageDefinition(),
                    PostgresConnectorErrorCode.USER_NOT_AUTHORIZED.getMessageDefinition(error.getClass().getName()));
        }
        catch (PropertyServerException error)
        {
            ExceptionHandler.handleException(auditLog,
                    this.getClass().getName(),
                    methodName, error,
                    PostgresConnectorAuditCode.PROPERTY_SERVER_EXCEPTION.getMessageDefinition(),
                    PostgresConnectorErrorCode.PROPERTY_SERVER_EXCEPTION.getMessageDefinition(error.getClass().getName()));
        }
        catch (ConnectorCheckedException error)
        {
            ExceptionHandler.handleException(auditLog,
                    this.getClass().getName(),
                    methodName, error,
                    PostgresConnectorAuditCode.CONNECTOR_CHECKED.getMessageDefinition(methodName),
                    PostgresConnectorErrorCode.CONNECTOR_CHECKED.getMessageDefinition(error.getClass().getName()));
        }

    }

    /**
     * @param postgresCol           the postgres column
     * @param  egeriaCol            the column data from egeria
     * @throws AlreadyHandledException
     */
    private void updateColumn(PostgresColumn postgresCol, DatabaseColumnElement egeriaCol ) throws AlreadyHandledException
    {
        String methodName = "updateColumn";

        try
        {
            if( !postgresCol.equals( egeriaCol))
            {
                DatabaseColumnProperties props = PostgresMapper.getColumnProperties( postgresCol );
                getContext().updateDatabaseColumn(egeriaCol.getElementHeader().getGUID(), props);
            }

        }
        catch (InvalidParameterException error)
        {
            ExceptionHandler.handleException(auditLog,
                    this.getClass().getName(),
                    methodName, error,
                    PostgresConnectorAuditCode.INVALID_PARAMETER.getMessageDefinition(methodName),
                    PostgresConnectorErrorCode.INVALID_PARAMETER.getMessageDefinition(error.getClass().getName()));
        }
        catch (PropertyServerException error)
        {
            ExceptionHandler.handleException(auditLog,
                    this.getClass().getName(),
                    methodName, error,
                    PostgresConnectorAuditCode.PROPERTY_SERVER_EXCEPTION.getMessageDefinition(),
                    PostgresConnectorErrorCode.PROPERTY_SERVER_EXCEPTION.getMessageDefinition(error.getClass().getName()));

        }
        catch (UserNotAuthorizedException error)
        {
            ExceptionHandler.handleException(auditLog,
                    this.getClass().getName(),
                    methodName, error,
                    PostgresConnectorAuditCode.USER_NOT_AUTORIZED.getMessageDefinition(),
                    PostgresConnectorErrorCode.USER_NOT_AUTHORIZED.getMessageDefinition(error.getClass().getName()));

        }
        catch (ConnectorCheckedException error)
        {
            ExceptionHandler.handleException(auditLog,
                    this.getClass().getName(),
                    methodName, error,
                    PostgresConnectorAuditCode.CONNECTOR_CHECKED.getMessageDefinition(methodName),
                    PostgresConnectorErrorCode.CONNECTOR_CHECKED.getMessageDefinition(error.getClass().getName()));
        }

    }

    /**
     * mapping function that reads tables, columns and primmary keys
     * for a schema from postgres and adds the data to egeria
     *
     * @param db the postgres attributes of the database
     * @throws ConnectorCheckedException
     */
    private void addDatabase(PostgresDatabase db) throws AlreadyHandledException
    {
        String methodName = "addDatabase";

        try
        {
         /*
         new database so build the database in egeria
         */
            DatabaseProperties dbProps = PostgresMapper.getDatabaseProperties(db);
            String guid = this.getContext().createDatabase(dbProps);
            addSchemas(db.getName(), guid);

        }
        catch (InvalidParameterException error)
        {
            ExceptionHandler.handleException(auditLog,
                    this.getClass().getName(),
                    methodName, error,
                    PostgresConnectorAuditCode.INVALID_PARAMETER.getMessageDefinition(methodName),
                    PostgresConnectorErrorCode.INVALID_PARAMETER.getMessageDefinition(error.getClass().getName()));

        }
        catch (PropertyServerException error)
        {
            ExceptionHandler.handleException(auditLog,
                    this.getClass().getName(),
                    methodName, error,
                    PostgresConnectorAuditCode.PROPERTY_SERVER_EXCEPTION.getMessageDefinition(),
                    PostgresConnectorErrorCode.PROPERTY_SERVER_EXCEPTION.getMessageDefinition(error.getClass().getName()));

        }
        catch (UserNotAuthorizedException error)
        {
            ExceptionHandler.handleException(auditLog,
                    this.getClass().getName(),
                    methodName, error,
                    PostgresConnectorAuditCode.USER_NOT_AUTORIZED.getMessageDefinition(),
                    PostgresConnectorErrorCode.USER_NOT_AUTHORIZED.getMessageDefinition(error.getClass().getName()));
        }
        catch (ConnectorCheckedException error)
        {
            ExceptionHandler.handleException(auditLog,
                    this.getClass().getName(),
                    methodName, error,
                    PostgresConnectorAuditCode.CONNECTOR_CHECKED.getMessageDefinition(methodName),
                    PostgresConnectorErrorCode.CONNECTOR_CHECKED.getMessageDefinition(error.getClass().getName()));
        }
    }

    /**
     * Adds schema entites to egeria for a given database
     *
     * @param dbName the name of the database
     * @param dbGUID the GUID of the datbase enitity to attach the schemas
     * @throws ConnectorCheckedException
     */
    private void addSchemas(String dbName, String dbGUID) throws AlreadyHandledException
    {

        String methodName = "addSchemas";

        try
        {
            PostgresSourceDatabase sourceDB = new PostgresSourceDatabase(this.connectionProperties);
            List<PostgresSchema> schemas = sourceDB.getDatabaseSchema(dbName);
            for (PostgresSchema sch : schemas)
            {
                addSchema(sch, dbGUID);
            }

        } catch (SQLException error)
        {
            ExceptionHandler.handleException(auditLog,
                    this.getClass().getName(),
                    methodName, error,
                    PostgresConnectorAuditCode.ERROR_READING_SCHEMAS.getMessageDefinition(),
                    PostgresConnectorErrorCode.ERROR_READING_SCHEMAS.getMessageDefinition(error.getClass().getName()));

        }
    }

    /**
     * mapping function that reads tables, columns and primmary keys
     * for a schema from postgres and adds the data to egeria
     *
     * @param sch     the postgres schema attributes to be
     * @param dbGuidd the egeria GUID of the database
     * @throws ConnectorCheckedException
     */
    private void addSchema(PostgresSchema sch, String dbGuidd) throws AlreadyHandledException
    {
        String methodName = "addSchema";
        try
        {
            DatabaseSchemaProperties schemaProps = PostgresMapper.getSchemaProperties(sch);

            String schemaGUID = getContext().createDatabaseSchema(dbGuidd, schemaProps);
            addTables(sch.getSchema_name(), schemaGUID);
            addViews(sch.getSchema_name(), schemaGUID);
            addForeignKeys(sch);
        }
        catch (InvalidParameterException error)
        {
            ExceptionHandler.handleException(auditLog,
                    this.getClass().getName(),
                    methodName, error,
                    PostgresConnectorAuditCode.INVALID_PARAMETER.getMessageDefinition(methodName),
                    PostgresConnectorErrorCode.INVALID_PARAMETER.getMessageDefinition(error.getClass().getName()));

        }
        catch (PropertyServerException error)
        {
            ExceptionHandler.handleException(auditLog,
                    this.getClass().getName(),
                    methodName, error,
                    PostgresConnectorAuditCode.PROPERTY_SERVER_EXCEPTION.getMessageDefinition(),
                    PostgresConnectorErrorCode.PROPERTY_SERVER_EXCEPTION.getMessageDefinition(error.getClass().getName()));

        }
        catch (UserNotAuthorizedException error)
        {
            ExceptionHandler.handleException(auditLog,
                    this.getClass().getName(),
                    methodName, error,
                    PostgresConnectorAuditCode.USER_NOT_AUTORIZED.getMessageDefinition(),
                    PostgresConnectorErrorCode.USER_NOT_AUTHORIZED.getMessageDefinition(error.getClass().getName()));
        }
        catch (ConnectorCheckedException error)
        {
            ExceptionHandler.handleException(auditLog,
                    this.getClass().getName(),
                    methodName, error,
                    PostgresConnectorAuditCode.CONNECTOR_CHECKED.getMessageDefinition(methodName),
                    PostgresConnectorErrorCode.CONNECTOR_CHECKED.getMessageDefinition(error.getClass().getName()));
        }
    }

    /**
     * mapping function that reads tables, columns and primmary keys
     * for a schema from postgres and adds the data to egeria
     *
     * @param schemaName the attributes of the schema which owns the tables
     * @param schemaGUID the GUID of the owning schema
     * @throws AlreadyHandledException
     */
    private void addTables(String schemaName, String schemaGUID) throws AlreadyHandledException
    {
        String methodName = "addTables";
        PostgresSourceDatabase source = new PostgresSourceDatabase(this.connectionProperties);

        try
        {
            /* add the schema tables */
            List<PostgresTable> tables = source.getTables(schemaName);
            for (PostgresTable table : tables)
            {
                addTable(table, schemaGUID);
            }
        }
        catch (SQLException error)
        {
            ExceptionHandler.handleException(auditLog,
                    this.getClass().getName(),
                    methodName, error,
                    PostgresConnectorAuditCode.ERROR_READING_TABLES.getMessageDefinition(),
                    PostgresConnectorErrorCode.ERROR_READING_TABLES.getMessageDefinition(error.getClass().getName()));

        }

    }

    /**
     * creates an egeria DatabaseTable entity for a given Postgres Table
     *
     * @param table      the postgres schema attributes to be
     * @param schemaGUID the egeria GUID of the schema
     * @throws ConnectorCheckedException
     */
    private void addTable(PostgresTable table, String schemaGUID) throws AlreadyHandledException
    {
        String methodName = "addTable";

        try
        {
            DatabaseTableProperties props = PostgresMapper.getTableProperties(table);
            String tableGUID = this.getContext().createDatabaseTable(schemaGUID, props);
            addColumns(table.getTable_name(), tableGUID);
        }
        catch (InvalidParameterException error)
        {
            ExceptionHandler.handleException(auditLog,
                    this.getClass().getName(),
                    methodName, error,
                    PostgresConnectorAuditCode.INVALID_PARAMETER.getMessageDefinition(methodName),
                    PostgresConnectorErrorCode.INVALID_PARAMETER.getMessageDefinition(error.getClass().getName()));

        }
        catch (PropertyServerException error)
        {
            ExceptionHandler.handleException(auditLog,
                    this.getClass().getName(),
                    methodName, error,
                    PostgresConnectorAuditCode.PROPERTY_SERVER_EXCEPTION.getMessageDefinition(),
                    PostgresConnectorErrorCode.PROPERTY_SERVER_EXCEPTION.getMessageDefinition(error.getClass().getName()));

        }
        catch (UserNotAuthorizedException error)
        {
            ExceptionHandler.handleException(auditLog,
                    this.getClass().getName(),
                    methodName, error,
                    PostgresConnectorAuditCode.USER_NOT_AUTORIZED.getMessageDefinition(),
                    PostgresConnectorErrorCode.USER_NOT_AUTHORIZED.getMessageDefinition(error.getClass().getName()));
        }
        catch (ConnectorCheckedException error)
        {
            ExceptionHandler.handleException(auditLog,
                    this.getClass().getName(),
                    methodName, error,
                    PostgresConnectorAuditCode.CONNECTOR_CHECKED.getMessageDefinition(methodName),
                    PostgresConnectorErrorCode.CONNECTOR_CHECKED.getMessageDefinition(error.getClass().getName()));
        }

    }

    /**
     * creates an egeria DatabaseView entity for a given Postgres Table
     * in postgres views are tables
     *
     * @param view       the postgres view properties
     * @param schemaGUID the egeria GUID of the schema
     * @throws AlreadyHandledException
     */
    private void addView(PostgresTable view, String schemaGUID) throws AlreadyHandledException
    {
        String methodName = "addTable";

        try
        {
            DatabaseTableProperties props = PostgresMapper.getTableProperties(view);
            String tableGUID = this.getContext().createDatabaseTable(schemaGUID, props);
            addColumns(view.getTable_name(), tableGUID);
        } catch (InvalidParameterException error)
        {
            ExceptionHandler.handleException(auditLog,
                    this.getClass().getName(),
                    methodName, error,
                    PostgresConnectorAuditCode.INVALID_PARAMETER.getMessageDefinition(methodName),
                    PostgresConnectorErrorCode.INVALID_PARAMETER.getMessageDefinition(error.getClass().getName()));

        } catch (PropertyServerException error)
        {
            ExceptionHandler.handleException(auditLog,
                    this.getClass().getName(),
                    methodName, error,
                    PostgresConnectorAuditCode.PROPERTY_SERVER_EXCEPTION.getMessageDefinition(),
                    PostgresConnectorErrorCode.PROPERTY_SERVER_EXCEPTION.getMessageDefinition(error.getClass().getName()));

        } catch (UserNotAuthorizedException error)
        {
            ExceptionHandler.handleException(auditLog,
                    this.getClass().getName(),
                    methodName, error,
                    PostgresConnectorAuditCode.USER_NOT_AUTORIZED.getMessageDefinition(),
                    PostgresConnectorErrorCode.USER_NOT_AUTHORIZED.getMessageDefinition(error.getClass().getName()));
        } catch (ConnectorCheckedException error)
        {
            ExceptionHandler.handleException(auditLog,
                    this.getClass().getName(),
                    methodName, error,
                    PostgresConnectorAuditCode.CONNECTOR_CHECKED.getMessageDefinition(methodName),
                    PostgresConnectorErrorCode.CONNECTOR_CHECKED.getMessageDefinition(error.getClass().getName()));
        }

    }


    /**
     * add the foregin keys to egeria
     * for a schema from postgres and adds the data to egeria
     *
     * @param schema the attributes of the schema which owns the tables
     * @throws AlreadyHandledException
     */
    private void addForeignKeys(PostgresSchema schema) throws AlreadyHandledException
    {
        String methodName = "addForeignKeys";
        int startFrom = 0;
        int pageSize = 100;
        PostgresSourceDatabase source = new PostgresSourceDatabase(this.connectionProperties);

        try
        {

            List<PostgresTable> tables = source.getTables(schema.getSchema_name());
            for (PostgresTable table : tables)
            {
                List<PostgresForeginKeyLinks> foreginKeys = source.getForeginKeyLinksForTable(table.getTable_name());
                List<String> importedGuids = new ArrayList<>();
                List<String> exportedGuids = new ArrayList<>();

                for (PostgresForeginKeyLinks link : foreginKeys)
                {
                    List<DatabaseColumnElement> importedEntities = getContext().findDatabaseColumns(link.getImportedColumnQualifiedName(), startFrom, pageSize);

                    if (importedEntities != null)
                    {
                        for (DatabaseColumnElement col : importedEntities)
                        {
                            importedGuids.add(col.getReferencedColumnGUID());
                        }
                    }

                    List<DatabaseColumnElement> exportedEntities = this.getContext().findDatabaseColumns(link.getExportedColumnQualifiedName(), startFrom, pageSize);

                    if (exportedEntities != null)
                    {
                        for (DatabaseColumnElement col : exportedEntities)
                        {
                            exportedGuids.add(col.getReferencedColumnGUID());
                        }
                    }


                    for (String str : importedGuids)
                    {
                        DatabaseForeignKeyProperties linkProps = new DatabaseForeignKeyProperties();
                        for (String s : exportedGuids)
                            getContext().addForeignKeyRelationship(str, s, linkProps);
                    }

                }
            }
        }
        catch (SQLException error)
        {
            ExceptionHandler.handleException(auditLog,
                    this.getClass().getName(),
                    methodName, error,
                    PostgresConnectorAuditCode.ERROR_READING_FOREIGN_KEYS.getMessageDefinition(),
                    PostgresConnectorErrorCode.ERROR_READING_FOREIGN_KEYS.getMessageDefinition(error.getClass().getName()));

        }
        catch (InvalidParameterException error)
        {
            ExceptionHandler.handleException(auditLog,
                    this.getClass().getName(),
                    methodName, error,
                    PostgresConnectorAuditCode.INVALID_PARAMETER.getMessageDefinition(methodName),
                    PostgresConnectorErrorCode.INVALID_PARAMETER.getMessageDefinition(error.getClass().getName()));

        }
        catch (PropertyServerException error)
        {
            ExceptionHandler.handleException(auditLog,
                    this.getClass().getName(),
                    methodName, error,
                    PostgresConnectorAuditCode.PROPERTY_SERVER_EXCEPTION.getMessageDefinition(),
                    PostgresConnectorErrorCode.PROPERTY_SERVER_EXCEPTION.getMessageDefinition(error.getClass().getName()));

        }
        catch (UserNotAuthorizedException error)
        {
            ExceptionHandler.handleException(auditLog,
                    this.getClass().getName(),
                    methodName, error,
                    PostgresConnectorAuditCode.USER_NOT_AUTORIZED.getMessageDefinition(),
                    PostgresConnectorErrorCode.USER_NOT_AUTHORIZED.getMessageDefinition(error.getClass().getName()));
        }
        catch (ConnectorCheckedException error)
        {
            ExceptionHandler.handleException(auditLog,
                    this.getClass().getName(),
                    methodName, error,
                    PostgresConnectorAuditCode.CONNECTOR_CHECKED.getMessageDefinition(methodName),
                    PostgresConnectorErrorCode.CONNECTOR_CHECKED.getMessageDefinition(error.getClass().getName()));
        }
    }

    /**
     * mapping function that reads tables, columns and primmary keys
     * for a schema from postgres and adds the data to egeria
     *
     * @param schemaName the attributes of the schema which owns the tables
     * @param schemaGUID the GUID of the owning schema
     * @throws ConnectorCheckedException thrown by the JDBC Driver
     */
    private void addViews(String schemaName, String schemaGUID) throws AlreadyHandledException
    {
        String methodName = "addViews";
        PostgresSourceDatabase source = new PostgresSourceDatabase(this.connectionProperties);

        try
        {
            List<PostgresTable> views = source.getViews(schemaName);

            for (PostgresTable view : views)
            {
                addView(view, schemaGUID);
            }


        } catch (SQLException error)
        {
            ExceptionHandler.handleException(auditLog,
                    this.getClass().getName(),
                    methodName, error,
                    PostgresConnectorAuditCode.ERROR_READING_VIEWS.getMessageDefinition(),
                    PostgresConnectorErrorCode.ERROR_READING_VIEWS.getMessageDefinition(error.getClass().getName()));

        }
    }

    /**
     * mapping function that reads tables, columns and primmary keys
     * for a schema from postgres and adds the data to egeria
     *
     * @param tableName the name of the parent table
     * @param tableGUID the GUID of the owning table
     * @throws AlreadyHandledException thrown by the JDBC Driver
     */
    private void addColumns(String tableName, String tableGUID) throws AlreadyHandledException
    {
        String methodName = "addColumns";
        PostgresSourceDatabase source = new PostgresSourceDatabase(this.connectionProperties);
        try
        {
            List<PostgresColumn> cols = source.getColumns(tableName);

            for (PostgresColumn col : cols)
            {
                addColumn(col, tableGUID);
            }
        } catch (SQLException error)
        {
            ExceptionHandler.handleException(auditLog,
                    this.getClass().getName(),
                    methodName, error,
                    PostgresConnectorAuditCode.ERROR_READING_COLUMNS.getMessageDefinition(),
                    PostgresConnectorErrorCode.ERROR_READING_COLUMNS.getMessageDefinition(error.getClass().getName()));

        }

    }

    /**
     * mapping function that reads columns and primmary keys
     * for a schema from postgres and creates
     *
     * @param col         the postgrews attributes of the column
     * @param guid        the GUID of the owning table
     * @throws AlreadyHandledException allows the exception to be passed up the stack, without additional handling
     */
    private void addColumn(PostgresColumn col, String guid) throws AlreadyHandledException
    {
        String methodName = "addColumn";
        try
        {
            DatabaseColumnProperties colProps = PostgresMapper.getColumnProperties(col);
            this.getContext().createDatabaseColumn(guid, colProps);

        } catch (InvalidParameterException error)
        {
            ExceptionHandler.handleException(auditLog,
                    this.getClass().getName(),
                    methodName, error,
                    PostgresConnectorAuditCode.INVALID_PARAMETER.getMessageDefinition(methodName),
                    PostgresConnectorErrorCode.INVALID_PARAMETER.getMessageDefinition(error.getClass().getName()));

        } catch (PropertyServerException error)
        {
            ExceptionHandler.handleException(auditLog,
                    this.getClass().getName(),
                    methodName, error,
                    PostgresConnectorAuditCode.PROPERTY_SERVER_EXCEPTION.getMessageDefinition(),
                    PostgresConnectorErrorCode.PROPERTY_SERVER_EXCEPTION.getMessageDefinition(error.getClass().getName()));

        } catch (UserNotAuthorizedException error)
        {
            ExceptionHandler.handleException(auditLog,
                    this.getClass().getName(),
                    methodName, error,
                    PostgresConnectorAuditCode.USER_NOT_AUTORIZED.getMessageDefinition(),
                    PostgresConnectorErrorCode.USER_NOT_AUTHORIZED.getMessageDefinition(error.getClass().getName()));
        } catch (ConnectorCheckedException error)
        {
            ExceptionHandler.handleException(auditLog,
                    this.getClass().getName(),
                    methodName, error,
                    PostgresConnectorAuditCode.CONNECTOR_CHECKED.getMessageDefinition(methodName),
                    PostgresConnectorErrorCode.CONNECTOR_CHECKED.getMessageDefinition(error.getClass().getName()));
        }
    }


    /**
     * Checks if any database tables need to be removed from egeria
     *
     * @param postgresTables              a list of the bean properties of a Postgres Database Tables
     * @param egeriaTables                a list of the Databases Tables already known to egeria
     * @throws AlreadyHandledException
     */
    private void deleteTables(List<PostgresTable> postgresTables, List<DatabaseTableElement> egeriaTables) throws AlreadyHandledException
    {

        String methodName = "deleteTables";
        int startFrom = 0;
        int pageSize = 100;

        try
        {
            if (egeriaTables != null)
            {
                /*
                for each datbase already known to egeria
                 */
                for (DatabaseTableElement element : egeriaTables)
                {
                    String knownName = element.getDatabaseTableProperties().getQualifiedName();
                    /*
                    check that the database is still present in postgres
                     */
                    for (PostgresTable table : postgresTables)
                    {
                        String sourceName = table.getQualifiedName();
                        if (sourceName.equals(knownName))
                        {
                            /*
                            if found then check the next databsee
                             */
                            break;
                        }
                        /*
                        not found in postgres , so delete the datase table from egeria
                         */
                        getContext().removeDatabaseTable(element.getElementHeader().getGUID(), knownName);
                    }
                }
            }
        }
        catch (InvalidParameterException error)
        {
            ExceptionHandler.handleException(auditLog,
                    this.getClass().getName(),
                    methodName, error,
                    PostgresConnectorAuditCode.INVALID_PARAMETER.getMessageDefinition(methodName),
                    PostgresConnectorErrorCode.INVALID_PARAMETER.getMessageDefinition(error.getClass().getName()));
        }
        catch (PropertyServerException error)
        {
            ExceptionHandler.handleException(auditLog,
                    this.getClass().getName(),
                    methodName, error,
                    PostgresConnectorAuditCode.PROPERTY_SERVER_EXCEPTION.getMessageDefinition(),
                    PostgresConnectorErrorCode.PROPERTY_SERVER_EXCEPTION.getMessageDefinition(error.getClass().getName()));
        }
        catch (UserNotAuthorizedException error)
        {
            ExceptionHandler.handleException(auditLog,
                    this.getClass().getName(),
                    methodName, error,
                    PostgresConnectorAuditCode.USER_NOT_AUTORIZED.getMessageDefinition(),
                    PostgresConnectorErrorCode.USER_NOT_AUTHORIZED.getMessageDefinition(error.getClass().getName()));

        }
        catch (ConnectorCheckedException error)
        {
            ExceptionHandler.handleException(auditLog,
                    this.getClass().getName(),
                    methodName, error,
                    PostgresConnectorAuditCode.CONNECTOR_CHECKED.getMessageDefinition(methodName),
                    PostgresConnectorErrorCode.CONNECTOR_CHECKED.getMessageDefinition(error.getClass().getName()));
        }
    }

    /**
     * Checks if any database tables need to be removed from egeria
     *
     * @param postgresColumns              a list of the bean properties of a Postgres Database Tables
     * @param egeriaColumns                a list of the Databases Tables already known to egeria
     * @throws AlreadyHandledException
     */
    private void deleteColumns(List<PostgresColumn> postgresColumns, List<DatabaseColumnElement> egeriaColumns) throws AlreadyHandledException
    {

        String methodName = "deleteColumns";
        int startFrom = 0;
        int pageSize = 100;

        try
        {
            if (egeriaColumns != null)
            {
                /*
                for each datbase already known to egeria
                 */
                for (DatabaseColumnElement element : egeriaColumns)
                {
                    String knownName = element.getDatabaseColumnProperties().getQualifiedName();
                    /*
                    check that the database column  is still present in postgres
                     */
                    for (PostgresColumn col : postgresColumns)
                    {
                        String sourceName = col.getQualifiedName();
                        if (sourceName.equals(knownName))
                        {
                            /*
                            if found then check the next databsee
                             */
                            break;
                        }
                        /*
                        not found in postgres , so delete the datase table from egeria
                         */
                        getContext().removeDatabaseColumn(element.getElementHeader().getGUID(), knownName);
                    }
                }
            }
        }
        catch (InvalidParameterException error)
        {
            ExceptionHandler.handleException(auditLog,
                    this.getClass().getName(),
                    methodName, error,
                    PostgresConnectorAuditCode.INVALID_PARAMETER.getMessageDefinition(methodName),
                    PostgresConnectorErrorCode.INVALID_PARAMETER.getMessageDefinition(error.getClass().getName()));
        }
        catch (PropertyServerException error)
        {
            ExceptionHandler.handleException(auditLog,
                    this.getClass().getName(),
                    methodName, error,
                    PostgresConnectorAuditCode.PROPERTY_SERVER_EXCEPTION.getMessageDefinition(),
                    PostgresConnectorErrorCode.PROPERTY_SERVER_EXCEPTION.getMessageDefinition(error.getClass().getName()));
        }
        catch (UserNotAuthorizedException error)
        {
            ExceptionHandler.handleException(auditLog,
                    this.getClass().getName(),
                    methodName, error,
                    PostgresConnectorAuditCode.USER_NOT_AUTORIZED.getMessageDefinition(),
                    PostgresConnectorErrorCode.USER_NOT_AUTHORIZED.getMessageDefinition(error.getClass().getName()));

        }
        catch (ConnectorCheckedException error)
        {
            ExceptionHandler.handleException(auditLog,
                    this.getClass().getName(),
                    methodName, error,
                    PostgresConnectorAuditCode.CONNECTOR_CHECKED.getMessageDefinition(methodName),
                    PostgresConnectorErrorCode.CONNECTOR_CHECKED.getMessageDefinition(error.getClass().getName()));
        }
    }
}
