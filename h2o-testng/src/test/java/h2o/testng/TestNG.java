package h2o.testng;

import h2o.testng.db.MySQL;
import h2o.testng.db.MySQLConfig;
import h2o.testng.utils.CommonHeaders;
import h2o.testng.utils.Dataset;
import h2o.testng.utils.FunctionUtils;
import h2o.testng.utils.Param;
import h2o.testng.utils.RecordingTestcase;
import hex.Model;
import hex.deeplearning.DeepLearningConfig;
import hex.glm.GLMConfig;
import hex.tree.drf.DRFConfig;
import hex.tree.gbm.GBMConfig;

import java.util.HashMap;

import org.apache.commons.lang.StringUtils;
import org.testng.Assert;
import org.testng.Reporter;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import water.H2O;
import water.TestNGUtil;

public class TestNG extends TestNGUtil {

	private static String algorithm = "";
	private static String size = null;
	private static String testcaseId = null;

	private static HashMap<Integer, Dataset> dataSetCharacteristic;

	@BeforeClass
	public void beforeClass() {

		//String dbConfigFilePath = System.getProperty("dbConfigFilePath");
		String dbConfigFilePath = "/Users/ece/0xdata/h2o-dev/DBConfig.properties";

		algorithm = System.getProperty("algorithm");
		//algorithm = "drf";
		size = System.getProperty("size");
		//testcaseId = System.getProperty("testcaseId");
		testcaseId = "dl_testcase_13";

		if (StringUtils.isNotEmpty(testcaseId)) {
			algorithm = "";
			size = "";
		}

		dataSetCharacteristic = FunctionUtils.readDataSetCharacteristic();

		if (StringUtils.isNotEmpty(dbConfigFilePath)) {

			MySQLConfig.initConfig().setConfigFilePath(dbConfigFilePath);

			//MySQL.createTable();
		}

		System.out.println(String.format("run TestNG with algorithm: %s, size: %s, testcaseId: %s", algorithm, size,
				testcaseId));

	}

	@DataProvider(name = "SingleTestcase")
	public static Object[][] dataProvider() {

		Object[][] result = null;

		Object[][] data = FunctionUtils.readAllTestcase(dataSetCharacteristic, algorithm);

		if (data == null || data.length == 0) {
			return null;
		}

		// set testcaseId
		if (StringUtils.isNotEmpty(testcaseId)) {

			result = new Object[1][data[0].length];
			int i = 0;

			for (i = 0; i < data.length; i++) {
				if (testcaseId.equals(data[i][0])) {
					result[0] = data[i];
					break;
				}
			}

			if (i == data.length) {
				System.out.println("Can not find testcase_id:" + testcaseId);
				return null;
			}

			return result;
		}

		// set size
		result = FunctionUtils.removeAllTestcase(data, size);
		return result;
	}

	@Test(dataProvider = "SingleTestcase")
	public void basic(String testcase_id, String test_description, String train_dataset_id, String validate_dataset_id,
			Dataset train_dataset, Dataset validate_dataset, String algorithm, boolean isNegativeTestcase,
			HashMap<String, String> rawInput) {

		String testcaseStatus = "PASSED";

		RecordingTestcase rt = new RecordingTestcase();

		Param[] params = null;

		Model.Parameters modelParameter = null;
		String invalidMessage = null;
		String notImplMessage = null;

		redirectStandardStreams();

		switch (algorithm) {
			case FunctionUtils.drf:
				params = DRFConfig.params;
				break;

			case FunctionUtils.gbm:
				params = GBMConfig.params;
				break;

			case FunctionUtils.glm:
				params = GLMConfig.params;
				break;

			case FunctionUtils.dl:
				params = DeepLearningConfig.params;
				break;

			default:
				System.out.println("Do not implement for algorithm: " + algorithm);
		}

		try {
			invalidMessage = FunctionUtils.validate(params, train_dataset_id, train_dataset, validate_dataset_id,
					validate_dataset, rawInput);
			if (FunctionUtils.drf.equals(algorithm)) {
				notImplMessage = FunctionUtils.checkImplemented(rawInput);
			}

			if (StringUtils.isNotEmpty(invalidMessage)) {
				System.out.println(invalidMessage);
				Assert.fail(String.format(invalidMessage));
			}
			else if (StringUtils.isNotEmpty(notImplMessage)) {
				System.out.println(notImplMessage);
				Assert.fail(String.format(notImplMessage));
			}
			else {
				// TODO: move modelParameter from here to FunctionUtils
				modelParameter = FunctionUtils.toModelParameter(params, algorithm, train_dataset_id,
						validate_dataset_id, train_dataset, validate_dataset, rawInput);

				FunctionUtils.basicTesting(algorithm, modelParameter, isNegativeTestcase, rawInput);
			}

		}
		catch (AssertionError ae) {
			testcaseStatus = "FAILED";
			throw ae;
		}
		finally {
			System.out.println("Total nodes: " + H2O.CLOUD.size());
			System.out.println("Total cores: " + H2O.NUMCPUS);
			System.out.println("Total time: " + (rt.getTimeRecording()) + "millis");
			// TODO: get memory by H2O's API
			System.out.println("Total memory used in testcase:" + (rt.getUsedMemory() / RecordingTestcase.MB) + "MB");

			resetStandardStreams();

			testcaseStatus = String.format("Testcase %s %s", rawInput.get(CommonHeaders.testcase_id), testcaseStatus);
			Reporter.log(testcaseStatus, true);
		}
	}

	@AfterClass
	public void afterClass() {
		FunctionUtils.closeAllFrameInDatasetCharacteristic(dataSetCharacteristic);
	}
}
