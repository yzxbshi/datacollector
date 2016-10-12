/**
 * Copyright 2015 StreamSets Inc.
 *
 * Licensed under the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.streamsets.pipeline.stage.destination.lib;

import com.streamsets.pipeline.api.ConfigDef;
import com.streamsets.pipeline.api.Dependency;
import com.streamsets.pipeline.api.FieldSelectorModel;
import com.streamsets.pipeline.api.Stage;
import com.streamsets.pipeline.api.ValueChooserModel;
import com.streamsets.pipeline.config.AvroCompression;
import com.streamsets.pipeline.config.AvroCompressionChooserValues;
import com.streamsets.pipeline.config.AvroSchemaLookupMode;
import com.streamsets.pipeline.config.DestinationAvroSchemaLookupModeChooserValues;
import com.streamsets.pipeline.config.CharsetChooserValues;
import com.streamsets.pipeline.config.ChecksumAlgorithm;
import com.streamsets.pipeline.config.ChecksumAlgorithmChooserValues;
import com.streamsets.pipeline.config.CsvHeader;
import com.streamsets.pipeline.config.CsvHeaderChooserValues;
import com.streamsets.pipeline.config.CsvMode;
import com.streamsets.pipeline.config.CsvModeChooserValues;
import com.streamsets.pipeline.config.DataFormat;
import com.streamsets.pipeline.config.DestinationAvroSchemaSource;
import com.streamsets.pipeline.config.DestinationAvroSchemaSourceChooserValues;
import com.streamsets.pipeline.config.JsonMode;
import com.streamsets.pipeline.config.JsonModeChooserValues;
import com.streamsets.pipeline.config.WholeFileExistsAction;
import com.streamsets.pipeline.config.WholeFileExistsActionChooserValues;
import com.streamsets.pipeline.lib.el.MathEL;
import com.streamsets.pipeline.lib.el.RecordEL;
import com.streamsets.pipeline.lib.el.StringEL;
import com.streamsets.pipeline.lib.generator.DataGeneratorFactory;
import com.streamsets.pipeline.lib.generator.DataGeneratorFactoryBuilder;
import com.streamsets.pipeline.lib.generator.binary.BinaryDataGeneratorFactory;
import com.streamsets.pipeline.lib.generator.delimited.DelimitedDataGeneratorFactory;
import com.streamsets.pipeline.lib.generator.text.TextDataGeneratorFactory;
import com.streamsets.pipeline.lib.generator.wholefile.WholeFileDataGeneratorFactory;
import com.streamsets.pipeline.lib.util.AvroTypeUtil;
import com.streamsets.pipeline.lib.util.DelimitedDataConstants;
import com.streamsets.pipeline.lib.util.ProtobufConstants;
import com.streamsets.pipeline.stage.common.DataFormatConfig;
import com.streamsets.pipeline.stage.common.DataFormatErrors;
import com.streamsets.pipeline.stage.common.DataFormatGroups;
import org.apache.avro.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static com.streamsets.pipeline.config.DestinationAvroSchemaSource.HEADER;
import static com.streamsets.pipeline.config.DestinationAvroSchemaSource.INLINE;
import static com.streamsets.pipeline.lib.util.AvroSchemaHelper.COMPRESSION_CODEC_KEY;
import static com.streamsets.pipeline.lib.util.AvroSchemaHelper.DEFAULT_VALUES_KEY;
import static com.streamsets.pipeline.lib.util.AvroSchemaHelper.INCLUDE_SCHEMA_KEY;
import static com.streamsets.pipeline.lib.util.AvroSchemaHelper.SCHEMA_ID_KEY;
import static com.streamsets.pipeline.lib.util.AvroSchemaHelper.SCHEMA_KEY;
import static com.streamsets.pipeline.lib.util.AvroSchemaHelper.SCHEMA_REPO_URLS_KEY;
import static com.streamsets.pipeline.lib.util.AvroSchemaHelper.SCHEMA_SOURCE_KEY;
import static com.streamsets.pipeline.lib.util.AvroSchemaHelper.SUBJECT_KEY;
import static org.apache.commons.lang.StringUtils.isEmpty;

public class DataGeneratorFormatConfig implements DataFormatConfig{
  private final static Logger LOG = LoggerFactory.getLogger(DataGeneratorFormatConfig.class);

  @ConfigDef(
    required = true,
    type = ConfigDef.Type.MODEL,
    defaultValue = "UTF-8",
    label = "Charset",
    displayPosition = 300,
    group = "#0",
    dependsOn = "dataFormat^",
    triggeredByValue = {"TEXT", "JSON", "DELIMITED"}
  )
  @ValueChooserModel(CharsetChooserValues.class)
  public String charset;

  /** For DELIMITED Content **/

  @ConfigDef(
    required = false,
    type = ConfigDef.Type.MODEL,
    defaultValue = "CSV",
    label = "Delimiter Format",
    description = "",
    displayPosition = 310,
    group = "DELIMITED",
    dependsOn = "dataFormat^",
    triggeredByValue = "DELIMITED"
  )
  @ValueChooserModel(CsvModeChooserValues.class)
  public CsvMode csvFileFormat;

  @ConfigDef(
    required = true,
    type = ConfigDef.Type.MODEL,
    defaultValue = "NO_HEADER",
    label = "Header Line",
    description = "",
    displayPosition = 320,
    group = "DELIMITED",
    dependsOn = "dataFormat^",
    triggeredByValue = "DELIMITED"
  )
  @ValueChooserModel(CsvHeaderChooserValues.class)
  public CsvHeader csvHeader;

  @ConfigDef(
    required = false,
    type = ConfigDef.Type.BOOLEAN,
    defaultValue = "true",
    label = "Replace New Line Characters",
    description = "Replaces new lines characters with configured string constant",
    displayPosition = 330,
    group = "DELIMITED",
    dependsOn = "dataFormat^",
    triggeredByValue = "DELIMITED"
  )
  public boolean csvReplaceNewLines;

  @ConfigDef(
    required = false,
    type = ConfigDef.Type.STRING,
    defaultValue = " ",
    label = "New Line Character Replacement",
    description = "String that will be used to substitute new line characters. Using empty string will remove the new line characters.",
    displayPosition = 335,
    group = "DELIMITED",
    dependsOn = "csvReplaceNewLines",
    triggeredByValue = "true"
  )
  public String csvReplaceNewLinesString;

  @ConfigDef(
    required = false,
    type = ConfigDef.Type.CHARACTER,
    defaultValue = "|",
    label = "Delimiter Character",
    displayPosition = 340,
    group = "DELIMITED",
    dependsOn = "csvFileFormat",
    triggeredByValue = "CUSTOM"
  )
  public char csvCustomDelimiter;

  @ConfigDef(
    required = false,
    type = ConfigDef.Type.CHARACTER,
    defaultValue = "\\",
    label = "Escape Character",
    displayPosition = 350,
    group = "DELIMITED",
    dependsOn = "csvFileFormat",
    triggeredByValue = "CUSTOM"
  )
  public char csvCustomEscape;

  @ConfigDef(
    required = false,
    type = ConfigDef.Type.CHARACTER,
    defaultValue = "\"",
    label = "Quote Character",
    displayPosition = 360,
    group = "DELIMITED",
    dependsOn = "csvFileFormat",
    triggeredByValue = "CUSTOM"
  )
  public char csvCustomQuote;

  /** For JSON **/

  @ConfigDef(
    required = true,
    type = ConfigDef.Type.MODEL,
    defaultValue = "MULTIPLE_OBJECTS",
    label = "JSON Content",
    description = "",
    displayPosition = 370,
    group = "JSON",
    dependsOn = "dataFormat^",
    triggeredByValue = "JSON"
  )
  @ValueChooserModel(JsonModeChooserValues.class)
  public JsonMode jsonMode;

  /** For TEXT Content **/

  @ConfigDef(
    required = true,
    type = ConfigDef.Type.MODEL,
    defaultValue = "/",
    label = "Text Field Path",
    description = "String field that will be written to the destination",
    displayPosition = 380,
    group = "TEXT",
    dependsOn = "dataFormat^",
    triggeredByValue = "TEXT"
  )
  @FieldSelectorModel(singleValued = true)
  public String textFieldPath;

  @ConfigDef(
      // not required since an empty separator is acceptable
      required = false,
      type = ConfigDef.Type.STRING,
      defaultValue = TextDataGeneratorFactory.RECORD_SEPARATOR_DEFAULT,
      label = "Record Separator",
      description = "Value to insert in output between records, defaults to newline",
      displayPosition = 385,
      group = "TEXT",
      dependsOn = "dataFormat^",
      triggeredByValue = "TEXT",
      evaluation = ConfigDef.Evaluation.EXPLICIT
  )
  public String textRecordSeparator = TextDataGeneratorFactory.RECORD_SEPARATOR_DEFAULT;

  @ConfigDef(
    required = true,
    type = ConfigDef.Type.BOOLEAN,
    defaultValue = "false",
    label = "Insert Record Separator If No Text",
    description = "Specifies whether a record separator should be inserted in output even after an empty value (no text in field)",
    displayPosition = 390,
    group = "TEXT",
    dependsOn = "dataFormat^",
    triggeredByValue = "TEXT"
  )
  public boolean textEmptyLineIfNull;

  /** For AVRO Content **/

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.MODEL,
      label = "Avro Schema Location",
      description = "Where to load the Avro Schema from.",
      displayPosition = 400,
      dependsOn = "dataFormat^",
      triggeredByValue = "AVRO",
      group = "AVRO"
  )
  @ValueChooserModel(DestinationAvroSchemaSourceChooserValues.class)
  public DestinationAvroSchemaSource avroSchemaSource;

  @ConfigDef(
      required = false,
      type = ConfigDef.Type.TEXT,
      label = "Avro Schema",
      description = "Overrides the schema included in the data (if any). Optionally use the " +
          "runtime:loadResource function to use a schema stored in a file",
      displayPosition = 410,
      group = "AVRO",
      dependencies = {
          @Dependency(configName = "dataFormat^", triggeredByValues = "AVRO"),
          @Dependency(configName = "avroSchemaSource", triggeredByValues = "INLINE")
      },
      mode = ConfigDef.Mode.JSON
  )
  public String avroSchema;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.BOOLEAN,
      label = "Register Schema",
      description = "Register the Avro schema in the Confluent Schema Registry",
      defaultValue = "false",
      dependencies = {
          @Dependency(configName = "dataFormat^", triggeredByValues = "AVRO"),
          @Dependency(configName = "avroSchemaSource", triggeredByValues = {"INLINE", "HEADER"}),
      },
      displayPosition = 420,
      group = "AVRO"
  )
  public boolean registerSchema;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.LIST,
      label = "Schema Registry URLs",
      description = "List of Confluent Schema Registry URLs",
      dependencies = {
          @Dependency(configName = "dataFormat^", triggeredByValues = "AVRO"),
          @Dependency(configName = "registerSchema", triggeredByValues = "true")
      },
      displayPosition = 430,
      group = "AVRO"

  )
  // This config property is duplicated for when registering schemas specified inline.
  // We can't do an AND+OR relationship with dependencies so this is a workaround.
  // See JIRA for API-55
  public List<String> schemaRegistryUrlsForRegistration = new ArrayList<>();

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.LIST,
      label = "Schema Registry URLs",
      description = "List of Confluent Schema Registry URLs",
      dependencies = {
          @Dependency(configName = "dataFormat^", triggeredByValues = "AVRO"),
          @Dependency(configName = "avroSchemaSource", triggeredByValues = "REGISTRY")
      },
      displayPosition = 431,
      group = "AVRO"

  )
  public List<String> schemaRegistryUrls = new ArrayList<>();

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.MODEL,
      label = "Lookup Schema By",
      description = "Whether to look up the Avro Schema by ID or fetch the latest schema for a Subject.",
      defaultValue = "SUBJECT",
      dependsOn = "avroSchemaSource",
      triggeredByValue = "REGISTRY",
      displayPosition = 440,
      group = "AVRO"
  )
  @ValueChooserModel(DestinationAvroSchemaLookupModeChooserValues.class)
  public AvroSchemaLookupMode schemaLookupMode = AvroSchemaLookupMode.SUBJECT;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.STRING,
      label = "Schema Subject",
      dependencies = {
          @Dependency(configName = "dataFormat^", triggeredByValues = "AVRO"),
          @Dependency(configName = "avroSchemaSource", triggeredByValues = "REGISTRY"),
          @Dependency(configName = "schemaLookupMode", triggeredByValues = "SUBJECT")
      },
      displayPosition = 450,
      group = "AVRO"
  )
  public String subject;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.STRING,
      label = "Schema Subject",
      description = "If this and Schema Registry URLs are non-empty, will register the supplied schema.",
      dependencies = {
          @Dependency(configName = "dataFormat^", triggeredByValues = "AVRO"),
          @Dependency(configName = "registerSchema", triggeredByValues = "true")
      },
      displayPosition = 451,
      group = "AVRO"
  )
  // This config property is duplicated for when registering schemas specified inline.
  // We can't do an AND+OR relationship with dependencies so this is a workaround.
  // See JIRA for API-55
  public String subjectToRegister;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.NUMBER,
      label = "Schema ID",
      min = 1,
      dependencies = {
          @Dependency(configName = "dataFormat^", triggeredByValues = "AVRO"),
          @Dependency(configName = "schemaLookupMode", triggeredByValues = "ID")
      },
      displayPosition = 460,
      group = "AVRO"
  )
  public int schemaId;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.BOOLEAN,
      defaultValue = "true",
      label = "Include Schema",
      description = "Includes the Avro schema in the output",
      displayPosition = 470,
      group = "AVRO",
      dependencies = {
          @Dependency(configName = "dataFormat^", triggeredByValues = "AVRO"),
          @Dependency(configName = "avroSchemaSource", triggeredByValues = {"INLINE", "HEADER"})
      }
  )
  public boolean includeSchema = true;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.MODEL,
      defaultValue = "NULL",
      label = "Avro Compression Codec",
      displayPosition = 480,
      group = "AVRO",
      dependsOn = "dataFormat^",
      triggeredByValue = "AVRO"
  )
  @ValueChooserModel(AvroCompressionChooserValues.class)
  public AvroCompression avroCompression = AvroCompression.NULL;

  /** For Binary Content **/

  @ConfigDef(
    required = true,
    type = ConfigDef.Type.MODEL,
    defaultValue = "/",
    label = "Binary Field Path",
    description = "Field to write data to Kafka",
    displayPosition = 420,
    group = "BINARY",
    dependsOn = "dataFormat^",
    triggeredByValue = "BINARY",
    elDefs = {StringEL.class}
  )
  @FieldSelectorModel(singleValued = true)
  public String binaryFieldPath = "/";

  /** For Protobuf Content **/

  @ConfigDef(
    required = true,
    type = ConfigDef.Type.STRING,
    defaultValue = "",
    label = "Protobuf Descriptor File",
    description = "Protobuf Descriptor File (.desc) path relative to SDC resources directory",
    displayPosition = 430,
    group = "PROTOBUF",
    dependsOn = "dataFormat^",
    triggeredByValue = "PROTOBUF"
  )
  public String protoDescriptorFile;

  @ConfigDef(
    required = true,
    type = ConfigDef.Type.STRING,
    defaultValue = "",
    description = "Fully Qualified Message Type name. Use format <packageName>.<messageTypeName>",
    label = "Message Type",
    displayPosition = 440,
    group = "PROTOBUF",
    dependsOn = "dataFormat^",
    triggeredByValue = "PROTOBUF"
  )
  public String messageType;

  /** For Whole File Content **/
  @ConfigDef(
      required = true,
      type = ConfigDef.Type.STRING,
      elDefs = {RecordEL.class, StringEL.class, MathEL.class},
      evaluation = ConfigDef.Evaluation.EXPLICIT,
      defaultValue = "${record:value('/fileInfo/filename')}",
      description = "File Name Expression",
      label = "File Name Expression",
      displayPosition = 450,
      group = "WHOLE_FILE",
      dependsOn = "dataFormat^",
      triggeredByValue = "WHOLE_FILE"
  )
  public String fileNameEL;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.MODEL,
      defaultValue = "TO_ERROR",
      label = "File Exists",
      description = "The action to perform when the file already exists.",
      displayPosition = 470,
      group = "WHOLE_FILE",
      dependsOn = "dataFormat^",
      triggeredByValue = "WHOLE_FILE"
  )
  @ValueChooserModel(WholeFileExistsActionChooserValues.class)
  public WholeFileExistsAction wholeFileExistsAction = WholeFileExistsAction.TO_ERROR;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.BOOLEAN,
      defaultValue = "false",
      label = "Event Includes Checksum",
      description = "File Transfer Event should include the checksum of the stream.",
      displayPosition = 480,
      group = "WHOLE_FILE",
      dependsOn = "dataFormat^",
      triggeredByValue = "WHOLE_FILE"
  )
  public boolean includeChecksumInTheEvents = false;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.MODEL,
      defaultValue = "MD5",
      label = "Checksum Algorithm",
      description = "The checksum algorithm for calculating checksum for the file.",
      displayPosition = 490,
      group = "WHOLE_FILE",
      dependsOn = "includeChecksumInTheEvents",
      triggeredByValue = "true"
  )
  @ValueChooserModel(ChecksumAlgorithmChooserValues.class)
  public ChecksumAlgorithm checksumAlgorithm = ChecksumAlgorithm.MD5;

/** End Config Defs **/

  private DataGeneratorFactory dataGeneratorFactory;

  public DataGeneratorFactory getDataGeneratorFactory() {
    return dataGeneratorFactory;
  }

  @Override
  public boolean init(
      Stage.Context context,
      DataFormat dataFormat,
      String groupName,
      String configPrefix,
      List<Stage.ConfigIssue> issues
  ) {
    boolean valid = true;
    switch (dataFormat) {
      case TEXT:
        valid = validateTextFormat(context, configPrefix, issues);
        break;
      case BINARY:
        valid = validateBinaryFormat(context, configPrefix, issues);
        break;
      case JSON:
      case DELIMITED:
      case SDC_JSON:
      case AVRO:
        // no-op
        break;
      case PROTOBUF:
        valid = validateProtobufFormat(context, configPrefix, issues);
        break;
      case WHOLE_FILE:
        valid = validateWholeFileFormat(context, configPrefix, issues);
        break;
      default:
        issues.add(context.createConfigIssue(groupName, configPrefix, DataFormatErrors.DATA_FORMAT_04, dataFormat));
        valid = false;
    }

    valid &= validateDataGenerator(context, dataFormat, groupName, configPrefix, issues);

    return valid;
  }

  private boolean validateDataGenerator (
      Stage.Context context,
      DataFormat dataFormat,
      String groupName,
      String configPrefix,
      List<Stage.ConfigIssue> issues
  ) {
    boolean valid = true;

    DataGeneratorFactoryBuilder builder = new DataGeneratorFactoryBuilder(context,
      dataFormat.getGeneratorFormat());
    if(charset == null || charset.trim().isEmpty()) {
      charset = StandardCharsets.UTF_8.name();
    }

    Charset cSet;
    try {
      cSet = Charset.forName(charset);
    } catch (UnsupportedCharsetException ex) {
      // setting it to a valid one so the parser factory can be configured and tested for more errors
      cSet = StandardCharsets.UTF_8;
      issues.add(
          context.createConfigIssue(
              groupName,
              configPrefix + ".charset",
              DataFormatErrors.DATA_FORMAT_05, charset
          )
      );
      valid = false;
    }

    builder.setCharset(cSet);

    switch (dataFormat) {
      case DELIMITED:
        configureDelimitedDataGenerator(builder);
        break;
      case TEXT:
        builder.setConfig(TextDataGeneratorFactory.FIELD_PATH_KEY, textFieldPath);
        builder.setConfig(TextDataGeneratorFactory.RECORD_SEPARATOR_IF_NULL_KEY, textEmptyLineIfNull);
        builder.setConfig(TextDataGeneratorFactory.RECORD_SEPARATOR_KEY, textRecordSeparator);
        break;
      case JSON:
        builder.setMode(jsonMode);
        break;
      case AVRO:
        valid &= configureAvroDataGenerator(context, configPrefix, issues, builder);
        break;
      case BINARY:
        builder.setConfig(BinaryDataGeneratorFactory.FIELD_PATH_KEY, binaryFieldPath);
        break;
      case PROTOBUF:
        builder.setConfig(ProtobufConstants.PROTO_DESCRIPTOR_FILE_KEY, protoDescriptorFile)
          .setConfig(ProtobufConstants.MESSAGE_TYPE_KEY, messageType);
        break;
      case WHOLE_FILE:
        builder.setConfig(WholeFileDataGeneratorFactory.INCLUDE_CHECKSUM_IN_THE_EVENTS_KEY, includeChecksumInTheEvents);
        builder.setConfig(WholeFileDataGeneratorFactory.CHECKSUM_ALGO_KEY, checksumAlgorithm);
        break;
      case SDC_JSON:
      default:
        // no action needed
        break;
    }
    if(valid) {
      try {
        dataGeneratorFactory = builder.build();
      } catch (Exception ex) {
        LOG.error(DataFormatErrors.DATA_FORMAT_201.getMessage(), ex.toString(), ex);
        issues.add(context.createConfigIssue(null, null, DataFormatErrors.DATA_FORMAT_201, ex.toString(), ex));
        valid = false;
      }
    }
    return valid;
  }

  private void configureDelimitedDataGenerator(DataGeneratorFactoryBuilder builder) {
    builder.setMode(csvFileFormat);
    builder.setMode(csvHeader);
    if(csvReplaceNewLines) {
      builder.setConfig(DelimitedDataGeneratorFactory.REPLACE_NEWLINES_STRING_KEY, csvReplaceNewLinesString);
    }
    builder.setConfig(DelimitedDataConstants.DELIMITER_CONFIG, csvCustomDelimiter);
    builder.setConfig(DelimitedDataConstants.ESCAPE_CONFIG, csvCustomEscape);
    builder.setConfig(DelimitedDataConstants.QUOTE_CONFIG, csvCustomQuote);
  }

  private boolean configureAvroDataGenerator(
      Stage.Context context,
      String configPrefix,
      List<Stage.ConfigIssue> issues,
      DataGeneratorFactoryBuilder builder
  ) {
    boolean valid = true;
    Schema schema = null;
    Map<String, Object> defaultValues = new HashMap<>();
    if(avroSchemaSource == INLINE) {
      try {
        schema = AvroTypeUtil.parseSchema(avroSchema);
      } catch (Exception e) {
        issues.add(
          context.createConfigIssue(
            DataFormatGroups.AVRO.name(),
            configPrefix + ".avroSchema",
            DataFormatErrors.DATA_FORMAT_300,
            e.toString(),
            e
          )
        );
        valid = false;
      }
    }
    if(schema != null) {
      try {
        defaultValues.putAll(AvroTypeUtil.getDefaultValuesFromSchema(schema, new HashSet<String>()));
      } catch (IOException e) {
        issues.add(
            context.createConfigIssue(
                DataFormatGroups.AVRO.name(),
                configPrefix + ".avroSchema",
                DataFormatErrors.DATA_FORMAT_301,
                e.toString(),
                e
            )
        );
        valid = false;
      }

      builder.setConfig(SCHEMA_KEY, avroSchema);
      builder.setConfig(DEFAULT_VALUES_KEY, defaultValues);
    }

    builder.setConfig(SCHEMA_SOURCE_KEY, avroSchemaSource);
    builder.setConfig(SCHEMA_REPO_URLS_KEY, schemaRegistryUrls);

    if (avroSchemaSource == INLINE || avroSchemaSource == HEADER && registerSchema) {
      // Subject used for registering schema
      builder.setConfig(SUBJECT_KEY, subjectToRegister);
      builder.setConfig(SCHEMA_REPO_URLS_KEY, schemaRegistryUrlsForRegistration);
    } else if (schemaLookupMode == AvroSchemaLookupMode.SUBJECT) {
      // Subject used for looking up schema
      builder.setConfig(SUBJECT_KEY, subject);
    } else {
      // Schema ID used for looking up schema
      builder.setConfig(SCHEMA_ID_KEY, schemaId);
    }
    builder.setConfig(INCLUDE_SCHEMA_KEY, includeSchema);
    builder.setConfig(COMPRESSION_CODEC_KEY, avroCompression.getCodecName());

    return valid;
  }

  private boolean validateProtobufFormat(
      Stage.Context context, String configPrefix, List<Stage.ConfigIssue> issues
  ) {
    boolean valid = true;
    if (isEmpty(protoDescriptorFile)) {
      issues.add(context.createConfigIssue(DataFormatGroups.PROTOBUF.name(),
          configPrefix + ".protoDescriptorFile",
          DataFormatErrors.DATA_FORMAT_07
      ));
      valid = false;
    } else {
      File file = new File(context.getResourcesDirectory(), protoDescriptorFile);
      if (!file.exists()) {
        issues.add(context.createConfigIssue(DataFormatGroups.PROTOBUF.name(),
            configPrefix + ".protoDescriptorFile",
            DataFormatErrors.DATA_FORMAT_09,
            file.getAbsolutePath()
        ));
        valid = false;
      }
      if (isEmpty(messageType)) {
        issues.add(context.createConfigIssue(DataFormatGroups.PROTOBUF.name(),
            configPrefix + ".messageType",
            DataFormatErrors.DATA_FORMAT_08
        ));
        valid = false;
      }
    }
    return valid;
  }

  private boolean validateBinaryFormat(
      Stage.Context context, String configPrefix, List<Stage.ConfigIssue> issues
  ) {
    // required field configuration to be set and it is "/" by default
    boolean valid = true;
    if (isEmpty(binaryFieldPath)) {
      issues.add(
          context.createConfigIssue(
              DataFormatGroups.BINARY.name(),
              configPrefix + ".binaryFieldPath",
              DataFormatErrors.DATA_FORMAT_200
          )
      );
      valid = false;
    }
    return valid;
  }

  private boolean validateTextFormat(
      Stage.Context context, String configPrefix, List<Stage.ConfigIssue> issues
  ) {
    // required field configuration to be set and it is "/" by default
    boolean valid = true;
    if (isEmpty(textFieldPath)) {
      issues.add(context.createConfigIssue(DataFormatGroups.TEXT.name(),
          configPrefix + ".textFieldPath",
          DataFormatErrors.DATA_FORMAT_200
      ));
      valid = false;
    }
    return valid;
  }

  private boolean validateWholeFileFormat(Stage.Context context, String configPrefix, List<Stage.ConfigIssue> issues) {
    boolean valid = true;
    if (isEmpty(fileNameEL)) {
      issues.add(
          context.createConfigIssue(
              DataFormatGroups.WHOLE_FILE.name(),
              configPrefix + ".fileNameEL",
              DataFormatErrors.DATA_FORMAT_200
          )
      );
      valid = false;
    }
    return valid;
  }
}
