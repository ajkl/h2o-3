package hex.glm;

import h2o.testng.utils.OptionsGroupParam;
import h2o.testng.utils.Param;
import hex.glm.GLMModel.GLMParameters;
import hex.glm.GLMModel.GLMParameters.Family;
import hex.glm.GLMModel.GLMParameters.Solver;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import org.apache.commons.lang.StringUtils;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import water.Key;
import water.Scope;
import water.TestNGUtil;
import water.fvec.FVecTest;
import water.fvec.Frame;
import water.fvec.NFSFileVec;
import water.parser.ParseDataset;

public class GLMBasic extends TestNGUtil {

	// below are single param which can set automatically to a GLM Object
	private static Param[] params = new Param[] {
		
			new Param("_family", "Family", false, false),
			new Param("_solver", "Solver", false, false),

			// autoset items
			new Param("_alpha", "double[]"),
			new Param("_lambda", "double[]"),
			new Param("_standardize", "boolean"),
			new Param("_lambda_search", "boolean"),
			new Param("_nfolds", "int"),
			new Param("_ignore_const_cols", "boolean"),
			new Param("_offset_column", "String"),
			new Param("_weights_column", "String"),
			new Param("_non_negative", "boolean"),
			new Param("_intercept", "boolean"),
			new Param("_prior", "double"),
			new Param("_max_active_predictors", "int"),
			new Param("_ignored_columns", "String[]"),
			new Param("_response_column", "String"),
	};
	
	private static OptionsGroupParam familyOptionsParams = new OptionsGroupParam(
			new String[] {"gaussian", "binomial", "poisson", "gamma", "tweedie"},
			new Object[] {Family.gaussian, Family.binomial, Family.poisson, Family.gamma, Family.tweedie}
	); 
	
	private static OptionsGroupParam solverOptionsParams = new OptionsGroupParam(
			new String[] {"auto","irlsm", "lbfgs"},
			new Object[] {Solver.AUTO, Solver.IRLSM, Solver.L_BFGS}
	); 

	private static List<String> tcHeaders = new ArrayList<String>(Arrays.asList(
			"0",
			"1",
			"test_description",
			"testcase_id",

			// GLM Parameters
			"regression",
			"classification",
			"gaussian",
			"binomial",
			"poisson",
			"gamma",
			"tweedie",

			"auto",
			"irlsm",
			"lbfgs",
			
			"_nfolds",
			"fold_column",

			"_ignore_const_cols",
			"_offset_column",
			"_weights_column",
			"_alpha",
			"_lambda",
			"_lambda_search",
			"_standardize",
			"_non_negative",
			"betaConstraints",
			"lowerBound",
			"upperBound",
			"beta_given",
			"_intercept",
			"_prior",
			"_max_active_predictors",
			"distribution",
			"regression_balanced_unbalanced",
			"rows",
			"columns",
			"train_rows_after_split",
			"validation_rows_after_split",
			"parse_types",
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
			"_ignored_columns",
			"r",
			"scikit"
	));

	@DataProvider(name = "glmCases")
	public static Object[][] glmCases() {

		/**
		 * The first row of data is used to testing.
		 */
		final int firstRow = 4;
		final String testcaseFilePath = "h2o-testng/src/test/resources/glmCases.csv";

		Object[][] data = null;
		List<String> lines = null;

		try {
			// read data from file
			lines = Files.readAllLines(find_test_file_static(testcaseFilePath).toPath(), Charset.defaultCharset());

		}
		catch (IOException ignore) {
			System.out.println("Cannot open file: " + testcaseFilePath);
			ignore.printStackTrace();
			return null;
		}

		// remove headers
		lines.removeAll(lines.subList(0, firstRow));

		data = new Object[lines.size()][7];
		int r = 0;

		for (String line : lines) {
			String[] variables = line.trim().split(",", -1);

			data[r][0] = variables[tcHeaders.indexOf("testcase_id")];
			data[r][1] = variables[tcHeaders.indexOf("test_description")];
			data[r][2] = variables[tcHeaders.indexOf("train_dataset_id")];
			data[r][3] = variables[tcHeaders.indexOf("train_dataset_filename")];
			data[r][4] = variables[tcHeaders.indexOf("validate_dataset_id")];
			data[r][5] = variables[tcHeaders.indexOf("validate_dataset_filename")];
			data[r][6] = variables; // raw input stored here for debugging

			r++;
		}

		return data;
	}
	
	@Test(dataProvider = "glmCases")
	public void basic(String testcaseId, String testDescription,
					  String trainDatasetId, String trainDatasetFilename, String validateDatasetId, String validateDatasetFilename,
					  String[] rawInput) {
		redirectStandardStreams();

		System.out.println("");
		System.out.println(String.format("Datasets: \n" +
				"Train Dataset ID:      %s\n" +
				"Train Dataset File:    %s\n" +
				"Validate Dataset ID:   %s\n" +
				"Validate Dataset File: %s\n",
				trainDatasetId, trainDatasetFilename, validateDatasetId, validateDatasetFilename));
		System.out.println("");

		try {
			String errorMessage = validate(rawInput);

			if (errorMessage == null) {
				_basic(toGLMParameters(rawInput), trainDatasetId, trainDatasetFilename, validateDatasetId,
						validateDatasetFilename, rawInput);
			}
			else {
				System.out.println("[INVALID] " + errorMessage);
				Assert.fail(String.format("INVALID INPUT - this test is skipped. " + errorMessage));
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

	private void _basic(GLMParameters glmParams, String trainDatasetId, String trainDatasetFilename,
						String validateDatasetId, String validateDatasetFilename, String[] rawInput) {
		
//		System.out.println(String.format("Testcase: %s", testcaseId));
//		System.out.println(String.format("Description: %s", testDescription));
		System.out.println("GLM Params:");
		for (Param p: params) {
//			if (p.isAutoSet) {
				p.print(glmParams);
//			}
		}

		Frame trainFrame = null;
		Frame validateFrame = null;
		Frame betaConstraints = null;

		// Build the appropriate glm, given the above parameters
		Key modelKey = Key.make("model");
		GLM job = null;
		GLMModel model = null;
		Frame score = null;
		HashMap<String, Double> coef = null;

		trainFrame = glmParams._train.get();
		if (glmParams._valid != null) {
			validateFrame = glmParams._valid.get();
		}
		if (glmParams._beta_constraints != null) {
			betaConstraints = glmParams._beta_constraints.get();
		}

		try {
			Scope.enter();

			job = new GLM(modelKey, "basic glm test", glmParams);
			model = job.trainModel().get();

			// model = DKV.get(modelKey).get();

			coef = model.coefficients();

			score = model.score(trainFrame);
			System.out.println("Test is passed.");
		}
		catch (IllegalArgumentException ex) {
			// can't predict testcase
			Assert.fail("Test is failed. It can't predict");
			ex.printStackTrace();
		}
		finally {
			if (trainFrame != null) {
				trainFrame.delete();
			}
			if (validateFrame != null) {
				validateFrame.delete();
			}
			if (betaConstraints != null) {
				betaConstraints.delete();
			}
			if (model != null)
				model.delete();
			if (job != null)
				job.remove();
			Scope.exit();
		}
	}

	private static void printRawInput(String[] rawInput) {
		System.out.println("RAW INPUTS:");
		for (int i = 0; i < tcHeaders.size(); i++) {
			System.out.println(String.format("  %s: %s", tcHeaders.get(i), rawInput[i]));
		}
	}

	private static String validate(String[] rawInput) {

		System.out.println("Validate Parameters object with testcase: " + rawInput[tcHeaders.indexOf("testcase_id")]);
		printRawInput(rawInput);

		String result = null;

		for (Param p : params) {
			if (p.isAutoSet) {
				result = p.validate(rawInput[tcHeaders.indexOf(p.name)]);
				if (result != null) {
					return result;
				}
			}
		}

		String dataset_directory = rawInput[tcHeaders.indexOf("dataset_directory")].trim();
		String train_dataset_id = rawInput[tcHeaders.indexOf("train_dataset_id")].trim();
		String train_dataset_filename = rawInput[tcHeaders.indexOf("train_dataset_filename")].trim();
		String response_column = rawInput[tcHeaders.indexOf("_response_column")].trim();

		if (StringUtils.isEmpty(dataset_directory)) {
			result = "Dataset directory is empty";
		}
		else if (StringUtils.isEmpty(train_dataset_id) || StringUtils.isEmpty(train_dataset_filename)) {
			result = "Dataset files is empty";
		}
		else if (StringUtils.isEmpty(response_column)) {
			result = "_response_column is empty";
		}

		return result;
	}

	private static GLMParameters toGLMParameters(String[] rawInput) {

		GLMParameters glmParams = new GLMParameters();

		Family f = (Family) familyOptionsParams.getValue(rawInput, tcHeaders);
		Solver s = (Solver) solverOptionsParams.getValue(rawInput, tcHeaders);

		if (f != null) {
			glmParams._family = f;
		}
		if (s != null) {
			glmParams._solver = s;
		}

		for (Param p : params) {
			if (p.isAutoSet) {
				p.parseAndSet(glmParams, rawInput[tcHeaders.indexOf(p.name)]);
			}
		}
		
		Frame trainFrame = null;
		Frame validateFrame = null;
		Frame betaConstraints = null;

		boolean isBetaConstraints = Param.parseBoolean(rawInput[tcHeaders.indexOf("betaConstraints")]);
		String lowerBound = rawInput[tcHeaders.indexOf("lowerBound")];
		String upperBound = rawInput[tcHeaders.indexOf("upperBound")];
		
		//TODO: review it
		String datasetDirectory = rawInput[tcHeaders.indexOf("dataset_directory")] + "/testng/";

		// create train dataset
		File train_dataset = find_test_file_static(datasetDirectory + rawInput[tcHeaders.indexOf("train_dataset_filename")]);
		System.out.println("Is train dataset exist? If no, abort the test.\n");
		assert train_dataset.exists();
		NFSFileVec nfs_train_dataset = NFSFileVec.make(train_dataset);
		Key key_train_dataset = Key.make(rawInput[tcHeaders.indexOf("train_dataset_id")] + ".hex");
		trainFrame = ParseDataset.parse(key_train_dataset, nfs_train_dataset._key);
		glmParams._train = trainFrame._key;

		// create validate dataset
		File validate_dataset = find_test_file_static(datasetDirectory + rawInput[tcHeaders.indexOf("validate_dataset_filename")]);
		assert validate_dataset.exists();
		NFSFileVec nfs_validate_dataset = NFSFileVec.make(validate_dataset);
		Key key_validate_dataset = Key.make(rawInput[tcHeaders.indexOf("validate_dataset_id")] + ".hex");
		validateFrame = ParseDataset.parse(key_validate_dataset, nfs_validate_dataset._key);
		glmParams._valid = validateFrame._key;

		// the beta constraints are represented in a frame with columns: "names", "lower_bounds", "upper_bounds",
		// and "beta_given" (optional). Each row corresponds to a predictor in the GLM. "names" contains the predictor
		// names, "lower"/"upper_bounds", are the lower and upper bounds of beta, and "beta_given" is some supplied
		// starting values for the betas.

		// You need to construct the beta constraints frame. In order to do so, you need to know the names of the
		// predictor columns, the upper and lower bounds (these are the same for each predictor), and (optionally)
		// the beta given (we don't provide this in the test case spreadsheet yet).

		// In this example, "AGE", "RACE", and "GLEASON" are the predictor names. -.5 is the lower bound and .5 is the
		// upper bound. Clearly, this information could change for each test case, so you'll have to generalize this
		// method.
		//Key betaConsKey = Key.make("beta_constraints");
		//FVecTest.makeByteVec(betaConsKey, "names, lower_bounds, upper_bounds\n"+
		//		"AGE, -.5, .5\n"+
		//		"RACE, -.5, .5\n"+
		//		"GLEASON, -.5, .5");
		//betaConstraints = ParseDataset.parse(Key.make("beta_constraints.hex"), betaConsKey);
		//glmParams._beta_constraints = betaConstraints._key;

		if (isBetaConstraints) {
			// Here's an example of how to make the beta constraints frame.
			// First, represent the beta constraints frame as a string, for example:

			//"names, lower_bounds, upper_bounds\n"+
			//"AGE, -.5, .5\n"+
			//"RACE, -.5, .5\n"+
			//"GLEASON, -.5, .5"
			String betaConstraintsString = "names, lower_bounds, upper_bounds\n";
			List<String> predictorNames = Arrays.asList(trainFrame._names);
			//predictorNames.remove(glmParams._response_column); // remove the response column name. we only want predictors
			for(String name : predictorNames){
				if (!name.equals(glmParams._response_column)) {
					if(trainFrame.vec(name).isEnum()){ // need coefficient names for each level of a categorical column
						for(String level : trainFrame.vec(name).domain()){
							betaConstraintsString += name + "." + level + ", " + lowerBound + ", " + upperBound + "\n";
						}
					}
					else { // numeric columns only need one coefficient name
						betaConstraintsString += name + ", " + lowerBound + ", " + upperBound + "\n";
					}
				}
			}
			Key betaConsKey = Key.make("beta_constraints");
			FVecTest.makeByteVec(betaConsKey, betaConstraintsString);
			betaConstraints = ParseDataset.parse(Key.make("beta_constraints.hex"), betaConsKey);
			glmParams._beta_constraints = betaConstraints._key;
		}
		
		return glmParams;
	}
}
