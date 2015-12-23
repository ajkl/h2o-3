package h2o.testng.utils;

import java.io.File;

import org.apache.commons.lang.StringUtils;

import water.Key;
import water.TestNGUtil;
import water.fvec.Frame;
import water.fvec.NFSFileVec;
import water.parser.ParseDataset;
import water.parser.ParseSetup;
import water.parser.ParserType;

public class Dataset {

	private final String splitByRegex = ";";

	private String dataSetId;
	private String dataSetDirectory;
	private String fileName;
	private String responseColumn;
	private String[] columnNames;
	private String[] columnTypes;

	private boolean isAvailabel;
	private Frame frame;

	public Dataset(String dataSetId, String dataSetDirectory, String fileName, String responseColumn,
			String columnNames, String columnTypes) {

		String[] arrayColumnNames = null;
		String[] arrayColumnTypes = null;
		if (StringUtils.isNotEmpty(columnNames.trim())) {
			arrayColumnNames = columnNames.split(splitByRegex);
		}
		if (StringUtils.isNotEmpty(columnTypes.trim())) {
			arrayColumnTypes = columnTypes.split(splitByRegex);
		}

		initDataset(dataSetId, dataSetDirectory, fileName, responseColumn, arrayColumnNames, arrayColumnTypes);
	}

	public Dataset(String dataSetId, String dataSetDirectory, String fileName, String responseColumn,
			String[] columnNames, String[] columnTypes) {

		initDataset(dataSetId, dataSetDirectory, fileName, responseColumn, columnNames, columnTypes);
	}

	// ---------------------------------------------- //
	// public functions
	// ---------------------------------------------- //
	public boolean isAvailabel() {

		if (!isAvailabel) {
			System.out.println("Dataset characteristic is not availabel");
		}
		return isAvailabel;
	}

	public void closeFrame() {

		if (frame != null) {
			frame.remove();
			frame.delete();
		}
	}

	public Frame getFrame() {

		if (frame == null) {
			createFrame();
		}

		return frame;
	}

	public void printDataset() {

		System.out.println("dataSetId: " + dataSetId);
		System.out.println("dataSetDirectory: " + dataSetDirectory);
		System.out.println("fileName: " + fileName);
		System.out.println("responseColumn: " + responseColumn);

		System.out.print("columnNames: ");
		if (columnNames != null) {
			for (String e : columnNames) {
				System.out.print(e + ",");
			}
		}
		System.out.println();

		System.out.print("columnTypes: ");
		if (columnTypes != null) {
			for (String e : columnTypes) {
				System.out.print(e + ",");
			}
		}
		System.out.println();
	}

	// ---------------------------------------------- //
	// private functions
	// ---------------------------------------------- //
	private void initDataset(String dataSetId, String dataSetDirectory, String fileName, String responseColumn,
			String[] columnNames, String[] columnTypes) {

		this.dataSetId = dataSetId.trim();
		this.dataSetDirectory = dataSetDirectory.trim();
		this.fileName = fileName.trim();
		this.responseColumn = responseColumn.trim();
		this.columnNames = columnNames;
		this.columnTypes = columnTypes;
		this.frame = null;
		setAvailabel();
	}

	private void setAvailabel() {

		System.out.println("validate dataset characterictis: " + dataSetId);
		printDataset();

		isAvailabel = true;

		if (StringUtils.isEmpty(dataSetId) || StringUtils.isEmpty(dataSetDirectory) || StringUtils.isEmpty(fileName)
				|| StringUtils.isEmpty(responseColumn)) {
			isAvailabel = false;
		}
		else if (columnNames == null || columnNames.length == 0) {
			System.out.println("columnNames is empty");
			isAvailabel = false;
		}
		else if (columnTypes == null || columnTypes.length == 0) {
			System.out.println("columnTypes is empty");
			isAvailabel = false;
		}
	}

	private void createFrame() {

		System.out.println("Create frame with " + fileName);
		this.printDataset();

		if (!isAvailabel()) {
			System.out.println("Dataset is not available");
			return;
		}

		String filePath = null;
		Frame fr = null;
		File file = null;
		NFSFileVec nfs = null;
		Key key = null;
		ParseSetup ps = null;

		String skey = dataSetId + ".hex";

		if ("bigdata".equals(dataSetDirectory)) {
			filePath = "bigdata/laptop/testng/";
		}
		else {
			filePath = "smalldata/testng/";
		}

		file = TestNGUtil.find_test_file_static(filePath + fileName);
		if (file == null || !file.exists()) {
			System.out.println("cannot find dataset: " + filePath + fileName);
			assert file.exists();
		}

		nfs = NFSFileVec.make(file);
		key = Key.make(skey);

		try {
			ps = new ParseSetup(ParserType.CSV, (byte) ',', false, ParseSetup.HAS_HEADER, columnNames.length,
					columnNames, ParseSetup.strToColumnTypes(columnTypes), null, null, null);

			fr = ParseDataset.parse(key, new Key[] { nfs._key }, true, ps);
		}
		catch (Exception e) {
			nfs.remove();
			key.remove();
			throw e;
		}

		frame = fr;
	}

	// ---------------------------------------------- //
	// getters and setters functions
	// ---------------------------------------------- //
	public String getDataSetId() {

		return dataSetId;
	}

	public void setDataSetId(String dataSetId) {

		this.dataSetId = dataSetId;
	}

	public String getDataSetDirectory() {

		return dataSetDirectory;
	}

	public void setDataSetDirectory(String dataSetDirectory) {

		this.dataSetDirectory = dataSetDirectory;
	}

	public String getFileName() {

		return fileName;
	}

	public void setFileName(String fileName) {

		this.fileName = fileName;
	}

	public String getResponseColumn() {

		return responseColumn;
	}

	public void setResponseColumn(String responseColumn) {

		this.responseColumn = responseColumn;
	}

	public String[] getColumnNames() {

		return columnNames;
	}

	public void setColumnNames(String[] columnNames) {

		this.columnNames = columnNames;
	}

	public String[] getColumnTypes() {

		return columnTypes;
	}

	public void setColumnTypes(String[] columnTypes) {

		this.columnTypes = columnTypes;
	}
}
