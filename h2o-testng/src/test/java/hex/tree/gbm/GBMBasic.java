package hex.tree.gbm;

import h2o.testng.utils.OptionsGroupParam;
import h2o.testng.utils.Param;
import hex.Distributions.Family;
import hex.tree.gbm.GBMModel.GBMParameters;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.lang.StringUtils;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import water.Scope;
import water.TestNGUtil;
import water.fvec.Frame;

public class GBMBasic extends TestNGUtil {

	@DataProvider(name = "gbmCases")
	public static Object[][] gbmCases() {

		/**
		 * The first row of data is used to testing.
		 */
		final int firstRow = 5;
		final String testcaseFilePath = "h2o-testng/src/test/resources/gbmCases.csv";

		Object[][] data = null;
		List<String> lines = null;

		try {
			// read data from file
			lines = Files.readAllLines(find_test_file_static(testcaseFilePath).toPath(), Charset.defaultCharset());
		}
		catch (Exception ignore) {
			System.out.println("Cannot open file: " + testcaseFilePath);
			ignore.printStackTrace();
			return null;
		}

		// remove headers
		lines.removeAll(lines.subList(0, firstRow));

		data = new Object[lines.size()][8];
		int r = 0;
		for (String line : lines) {
			String[] variables = line.trim().split(",", -1);

			data[r][0] = variables[tcHeaders.indexOf("testcase_id")];
			data[r][1] = variables[tcHeaders.indexOf("test_description")];
			data[r][2] = variables[tcHeaders.indexOf("dataset_directory")];
			data[r][3] = variables[tcHeaders.indexOf("train_dataset_id")];
			data[r][4] = variables[tcHeaders.indexOf("train_dataset_filename")];
			data[r][5] = variables[tcHeaders.indexOf("validate_dataset_id")];
			data[r][6] = variables[tcHeaders.indexOf("validate_dataset_filename")];
			data[r][7] = variables;

			r++;
		}

		return data;
	}

	@Test(dataProvider = "gbmCases")
	public void basic(String testcase_id, String test_description, String dataset_directory, String train_dataset_id,
			String train_dataset_filename, String validate_dataset_id, String validate_dataset_filename,
			String[] rawInput) {

		GBMParameters gbmParams = null;

		redirectStandardStreams();

		try {
			String invalidMessage = validate(rawInput);

			if (invalidMessage != null) {
				System.out.println(invalidMessage);
				Assert.fail(String.format(invalidMessage));
			}
			else {
				gbmParams = toGBMParameters(dataset_directory, train_dataset_id, train_dataset_filename,
						validate_dataset_id, validate_dataset_filename, rawInput);
				_basic(testcase_id, test_description, gbmParams, rawInput);
			}
		}
		finally {

			// wait 100 mili-sec for output/error to be stored
			try {
				Thread.sleep(100);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}

			resetStandardStreams();
		}
	}

	private void _basic(String testcase_id, String test_description, GBMParameters parameter, String[] rawInput) {

		System.out.println(String.format("Testcase: %s", testcase_id));
		System.out.println(String.format("Description: %s", test_description));
		System.out.println("GBM Params:");
		for (Param p : params) {
			p.print(parameter);
		}

		Frame trainFrame = null;
		Frame validateFrame = null;
		GBM job = null;
		GBMModel gbmModel = null;
		Frame score = null;

		trainFrame = parameter._train.get();
		if (parameter._valid != null) {
			validateFrame = parameter._valid.get();
		}

		try {
			Scope.enter();

			// Build a first model; all remaining models should be equal
			job = new GBM(parameter);
			gbmModel = job.trainModel().get();

			score = gbmModel.score(trainFrame);
			// Assert.assertTrue(model.testJavaScoring(score, trainFrame, 1e-15));
			System.out.println("Test is passed.");
		}
		catch (IllegalArgumentException ex) {
			// can't predict testcase
			System.out.println("Test is failed. It can't predict");
			ex.printStackTrace();
			
			Assert.fail("Test is failed. It can't predict", ex);
		}
		finally {
			if (trainFrame != null) {
				trainFrame.remove();
				trainFrame.delete();
			}
			if (validateFrame != null) {
				validateFrame.remove();
				validateFrame.delete();
			}
			if (score != null) {
				score.remove();
				score.delete();
			}
			if (job != null) {
				job.remove();
			}
			if (gbmModel != null) {
				gbmModel.delete();
			}
			Scope.exit();
		}
	}

	private static String validate(String[] input) {

		System.out.println("Validate Parameters object with testcase: " + input[tcHeaders.indexOf("testcase_id")]);
		String result = null;

		for (Param p : params) {
			if (p.isAutoSet) {
				result = p.validate(input[tcHeaders.indexOf(p.name)]);
				if (result != null) {
					return result;
				}
			}
		}

		String dataset_directory = input[tcHeaders.indexOf("dataset_directory")].trim();
		String train_dataset_id = input[tcHeaders.indexOf("train_dataset_id")];
		String train_dataset_filename = input[tcHeaders.indexOf("train_dataset_filename")];
		String response_column = input[tcHeaders.indexOf("_response_column")];

		if (StringUtils.isEmpty(dataset_directory)) {
			result = "Dataset directory is empty";
		}
		else if (StringUtils.isEmpty(train_dataset_id) || StringUtils.isEmpty(train_dataset_filename)) {
			result = "Dataset files is empty";
		}
		else if (StringUtils.isEmpty(response_column)) {
			result = "_response_column is empty";
		}

		if (result != null) {
			result = "[INVALID] " + result;
		}

		return result;
	}

	private static GBMParameters toGBMParameters(String dataset_directory, String train_dataset_id,
			String train_dataset_filename, String validate_dataset_id, String validate_dataset_filename, String[] input) {

		System.out.println("Create Parameters object with testcase: " + input[tcHeaders.indexOf("testcase_id")]);

		GBMParameters gbmParams = new GBMParameters();

		// set AutoSet params
		for (Param p : params) {
			if (p.isAutoSet) {
				p.parseAndSet(gbmParams, input[tcHeaders.indexOf(p.name)]);
			}
		}

		// set distribution param
		Family f = (Family) familyParams.getValue(input, tcHeaders);

		if (f != null) {
			System.out.println("Set _distribution: " + f);
			gbmParams._distribution = f;
		}

		// set train/validate params
		Frame trainFrame = null;
		Frame validateFrame = null;

		if ("bigdata".equals(dataset_directory)) {
			dataset_directory = "bigdata/laptop/testng/";
		}
		else {
			dataset_directory = "smalldata/testng/";
		}

		try {

			System.out.println("Create train frame: " + train_dataset_filename);
			trainFrame = Param.createFrame(dataset_directory + train_dataset_filename, train_dataset_id);

			if (StringUtils.isNotEmpty(validate_dataset_filename)) {
				System.out.println("Create validate frame: " + validate_dataset_filename);
				validateFrame = Param.createFrame(dataset_directory + validate_dataset_filename, validate_dataset_id);
			}
		}
		catch (Exception e) {
			if (trainFrame != null) {
				trainFrame.remove();
			}
			if (validateFrame != null) {
				validateFrame.remove();
			}
			throw e;
		}

		System.out.println("Set train frame");
		gbmParams._train = trainFrame._key;

		if (validateFrame != null) {
			System.out.println("Set validate frame");
			gbmParams._valid = validateFrame._key;
		}

		System.out.println("Create success GBMParameters object.");
		return gbmParams;

	}
	
	private static Param[] params = new Param[] {
		
		new Param("_distribution", "Family", false, false),

		// autoset items
		new Param("_nfolds", "int"),
		new Param("_ignore_const_cols", "boolean"),
		new Param("_offset_column", "String"),
		new Param("_weights_column", "String"),
		new Param("_ntrees", "int"),
		new Param("_max_depth", "int"),
		new Param("_min_rows", "double"),
		new Param("_nbins", "int"),
		new Param("_nbins_cats", "int"),
		new Param("_score_each_iteration", "boolean"),
		new Param("_learn_rate", "float"),
		new Param("_balance_classes", "boolean"),
		new Param("_max_confusion_matrix_size", "int"),
		new Param("_max_hit_ratio_k", "int"),
		new Param("_r2_stopping", "double"),
		new Param("_build_tree_one_node", "boolean"),
		new Param("_class_sampling_factors", "float[]"),
		
		new Param("_response_column", "String"),
	}; 
	
	private static List<String> tcHeaders = new ArrayList<String>(Arrays.asList(
			"0",
			"1",
			"test_description",
			"testcase_id",

			// GBM Parameters
			"regression",
			"classification",
			
			"auto",
			"gaussian",
			"binomial",
			"multinomial",
			"poisson",
			"gamma",
			"tweedie",
			
			"_nfolds",
			"fold_column",
			"_ignore_const_cols",
			"_offset_column",
			"_weights_column",
			"_ntrees",
			"_max_depth",
			"_min_rows",
			"_nbins",
			"_nbins_cats",
			"_learn_rate",
			"_score_each_iteration",
			"_balance_classes",
			"_max_confusion_matrix_size",
			"_max_hit_ratio_k",
			"_r2_stopping",
			"_build_tree_one_node",
			"_class_sampling_factors",
			
			// testcase description
			"distribution",
			"regression_balanced_unbalanced",
			"rows",
			"columns",
			"train_rows_after_split",
			"validation_rows_after_split",
			"categorical",
			"sparse",
			"dense",
			"high-dimensional data",
			"correlated",
			"collinear_cols",

			// dataset files & ids
			"dataset_directory",
			"train_dataset_id",
			"train_dataset_filename",
			"validate_dataset_id",
			"validate_dataset_filename",

			"_response_column",
			"response_column_type",
			"ignored_columns",
			"R",
			"Scikit",
			"R_AUC",
			"R_MSE",
			"R_Loss",
			"Scikit_AUC",
			"Scikit_MSE",
			"Scikit_Loss"
	));
	
	//TODO: Family have no binomial attribute
	private final static OptionsGroupParam familyParams = new OptionsGroupParam(
			new String[] {"auto", "gaussian", "multinomial", "poisson", "gamma", "tweedie"},
			new Object[] {Family.AUTO, Family.gaussian, Family.multinomial, Family.poisson, Family.gamma, Family.tweedie});
}
