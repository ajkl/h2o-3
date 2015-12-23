package h2o.testng.utils;

import h2o.testng.db.MySQL;
import hex.*;
import hex.Distribution.Family;
import hex.deeplearning.DeepLearning;
import hex.deeplearning.DeepLearningConfig;
import hex.deeplearning.DeepLearningModel;
import hex.deeplearning.DeepLearningParameters;
import hex.glm.GLM;
import hex.glm.GLMConfig;
import hex.glm.GLMModel;
import hex.glm.GLMModel.GLMParameters;
import hex.glm.GLMModel.GLMParameters.Solver;
import hex.tree.drf.DRF;
import hex.tree.drf.DRFConfig;
import hex.tree.drf.DRFModel;
import hex.tree.gbm.GBM;
import hex.tree.gbm.GBMConfig;
import hex.tree.gbm.GBMModel;
import hex.tree.gbm.GBMModel.GBMParameters;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.StringUtils;
import org.testng.Assert;

import au.com.bytecode.opencsv.CSVReader;
import water.Key;
import water.Scope;
import water.TestNGUtil;
import water.fvec.FVecTest;
import water.fvec.Frame;
import water.parser.ParseDataset;

public class FunctionUtils {

	public final static String glm = "glm";
	public final static String gbm = "gbm";
	public final static String drf = "drf";
	public final static String dl = "dl";

	public final static String smalldata = "smalldata";
	public final static String bigdata = "bigdata";

	// ---------------------------------------------- //
	// execute testcase
	// ---------------------------------------------- //
	private static String validateTestcaseType(Dataset train_dataset, HashMap<String, String> rawInput) {

		if (train_dataset == null || !train_dataset.isAvailabel()) {
			return null;
		}

		String result = null;
		String[] columnNames = train_dataset.getColumnNames();
		String[] columnTypes = train_dataset.getColumnTypes();
		String reponseColumnType = columnTypes[ArrayUtils.indexOf(columnNames, train_dataset.getResponseColumn())];
		if (Param.parseBoolean(rawInput.get(CommonHeaders.classification))
				&& !"enum".equals(reponseColumnType.toLowerCase())) {

			result = "This is classification testcase but response_column type is not enum";
		}
		else if (Param.parseBoolean(rawInput.get(CommonHeaders.regression))
				&& "enum".equals(reponseColumnType.toLowerCase())) {

			result = "This is regresstion testcase but response_column type is enum";
		}

		return result;
	}

	public static String validate(Param[] params, String train_dataset_id, Dataset train_dataset,
			String validate_dataset_id, Dataset validate_dataset, HashMap<String, String> rawInput) {

		System.out.println("Validate Parameters object with testcase: " + rawInput.get(CommonHeaders.testcase_id));
		String result = null;

		String verifyTrainDSMessage = validateTestcaseType(train_dataset, rawInput);
		String verifyValidateDSMessage = validateTestcaseType(validate_dataset, rawInput);

		if (StringUtils.isEmpty(train_dataset_id)) {
			result = "In testcase, train dataset id is empty";
		}
		else if (train_dataset == null) {
			result = String.format("Dataset id %s do not have in dataset characteristic", train_dataset_id);
		}
		else if (!train_dataset.isAvailabel()) {
			result = String.format("Dataset id %s is not available in dataset characteristic", train_dataset_id);
		}
		else if (StringUtils.isNotEmpty(validate_dataset_id)
				&& (validate_dataset == null || !validate_dataset.isAvailabel())) {
			result = String.format("Dataset id %s is not available in dataset characteristic", validate_dataset_id);
		}
		else if (verifyTrainDSMessage != null) {
			result = verifyTrainDSMessage;
		}
		else if (verifyValidateDSMessage != null) {
			result = verifyValidateDSMessage;
		}
		else {
			result = Param.validateAutoSetParams(params, rawInput);

		}

		if (result != null) {
			result = "[INVALID] " + result;
		}

		return result;
	}

	/**
	 * Only use for DRF algorithm
	 * 
	 * @param tcHeaders
	 * @param input
	 * @return
	 */
	public static String checkImplemented(HashMap<String, String> rawInput) {

		System.out.println("check modelParameter object with testcase: " + rawInput.get(CommonHeaders.testcase_id));
		String result = null;

		if (StringUtils.isNotEmpty(rawInput.get(CommonHeaders.family_gaussian).trim())
				|| StringUtils.isNotEmpty(rawInput.get(CommonHeaders.family_binomial).trim())
				|| StringUtils.isNotEmpty(rawInput.get(CommonHeaders.family_multinomial).trim())
				|| StringUtils.isNotEmpty(rawInput.get(CommonHeaders.family_poisson).trim())
				|| StringUtils.isNotEmpty(rawInput.get(CommonHeaders.family_gamma).trim())
				|| StringUtils.isNotEmpty(rawInput.get(CommonHeaders.family_tweedie).trim())) {

			result = "Only AUTO family is implemented";
		}

		if (result != null) {
			result = "[NOT IMPL] " + result;
		}

		return result;
	}

	/**
	 * This function is only used for GLM algorithm.
	 * 
	 * @param tcHeaders
	 * @param rawInput
	 * @param trainFrame
	 * @param responseColumn
	 * @return null if testcase do not require betaconstraints otherwise return beta constraints frame
	 */
	private static Frame createBetaConstraints(HashMap<String, String> rawInput, Frame trainFrame, String responseColumn) {

		Frame betaConstraints = null;
		boolean isBetaConstraints = Param.parseBoolean(rawInput.get("betaConstraints"));
		String lowerBound = rawInput.get("lowerBound");
		String upperBound = rawInput.get("upperBound");

		if (!isBetaConstraints) {
			return null;
		}
		// Here's an example of how to make the beta constraints frame.
		// First, represent the beta constraints frame as a string, for example:

		// "names, lower_bounds, upper_bounds\n"+
		// "AGE, -.5, .5\n"+
		// "RACE, -.5, .5\n"+
		// "GLEASON, -.5, .5"
		String betaConstraintsString = "names, lower_bounds, upper_bounds\n";
		List<String> predictorNames = Arrays.asList(trainFrame._names);
		for (String name : predictorNames) {
			// ignore the response column and any constant column in bc.
			// we only want predictors
			if (!name.equals(responseColumn) && !trainFrame.vec(name).isConst()) {
				// need coefficient names for each level of a categorical column
				if (trainFrame.vec(name).isCategorical()) {
					for (String level : trainFrame.vec(name).domain()) {
						betaConstraintsString += String.format("%s.%s,%s,%s\n", name, level, lowerBound, upperBound);
					}
				}
				else { // numeric columns only need one coefficient name
					betaConstraintsString += String.format("%s,%s,%s\n", name, lowerBound, upperBound);
				}
			}
		}
		Key betaConsKey = Key.make("beta_constraints");
		FVecTest.makeByteVec(betaConsKey, betaConstraintsString);
		betaConstraints = ParseDataset.parse(Key.make("beta_constraints.hex"), betaConsKey);

		return betaConstraints;
	}

	public static Model.Parameters toModelParameter(Param[] params, String algorithm, String train_dataset_id,
			String validate_dataset_id, Dataset train_dataset, Dataset validate_dataset,
			HashMap<String, String> rawInput) {

		System.out.println("Create modelParameter object with testcase: " + rawInput.get(CommonHeaders.testcase_id));

		boolean isTunedValue = false;
		Model.Parameters modelParameter = null;

		switch (algorithm) {
			case FunctionUtils.drf:

				modelParameter = new DRFModel.DRFParameters();

				// set distribution param
				Family drfFamily = (Family) DRFConfig.familyParams.getValue(rawInput);

				if (drfFamily != null) {
					System.out.println("Set _distribution: " + drfFamily);
					modelParameter._distribution = drfFamily;

					isTunedValue = true;
				}
				break;

			case FunctionUtils.glm:

				modelParameter = new GLMParameters();

				hex.glm.GLMModel.GLMParameters.Family glmFamily = (hex.glm.GLMModel.GLMParameters.Family) GLMConfig.familyOptionsParams
						.getValue(rawInput);
				Solver s = (Solver) GLMConfig.solverOptionsParams.getValue(rawInput);

				if (glmFamily != null) {
					System.out.println("Set _family: " + glmFamily);
					((GLMParameters) modelParameter)._family = glmFamily;

					isTunedValue = true;
				}
				if (s != null) {
					System.out.println("Set _solver: " + s);
					((GLMParameters) modelParameter)._solver = s;

					isTunedValue = true;
				}
				break;

			case FunctionUtils.gbm:

				modelParameter = new GBMParameters();

				// set distribution param
				Family gbmFamily = (Family) GBMConfig.familyParams.getValue(rawInput);

				if (gbmFamily != null) {
					System.out.println("Set _distribution: " + gbmFamily);
					modelParameter._distribution = gbmFamily;

					isTunedValue = true;
				}
				break;
			case FunctionUtils.dl:

				modelParameter = new DeepLearningParameters();

				// set distribution param
				Family dlDistribution = (Family) DeepLearningConfig.distributionOptionsParams.getValue(rawInput);

				if (dlDistribution != null) {
					System.out.println("Set _distribution: " + dlDistribution);
					modelParameter._distribution = dlDistribution;

					isTunedValue = true;
				}

				DeepLearningParameters.Activation dlActivation = (DeepLearningParameters.Activation) DeepLearningConfig.activationOptionsParams
						.getValue(rawInput);

				if (dlActivation != null) {
					System.out.println("Set _activation: " + dlActivation);
					((DeepLearningParameters) modelParameter)._activation = dlActivation;

					isTunedValue = true;
				}

				DeepLearningParameters.Loss dlLoss = (DeepLearningParameters.Loss) DeepLearningConfig.lossOptionsParams
						.getValue(rawInput);

				if (dlLoss != null) {
					System.out.println("Set _activation: " + dlLoss);
					((DeepLearningParameters) modelParameter)._loss = dlLoss;

				}

				DeepLearningParameters.InitialWeightDistribution dlInitialWeightDistribution = (DeepLearningParameters.InitialWeightDistribution) DeepLearningConfig.initialWeightDistributionOptionsParams
						.getValueKey(rawInput, "_initial_weight_distribution");

				if (dlInitialWeightDistribution != null) {
					System.out.println("Set _initial_weight_distribution: " + dlInitialWeightDistribution);
					((DeepLearningParameters) modelParameter)._initial_weight_distribution = dlInitialWeightDistribution;

					isTunedValue = true;
				}

				DeepLearningParameters.MissingValuesHandling dlMissingValuesHandling = (DeepLearningParameters.MissingValuesHandling) DeepLearningConfig.missingValuesHandlingOptionsParams
						.getValueKey(rawInput, "_missing_values_handling");

				if (dlMissingValuesHandling != null) {
					System.out.println("Set _missing_values_handling: " + dlMissingValuesHandling);
					((DeepLearningParameters) modelParameter)._missing_values_handling = dlMissingValuesHandling;

					isTunedValue = true;
				}

				break;

			default:
				System.out.println("can not parse to object parameter with algorithm: " + algorithm);
		}

		// set AutoSet params
		isTunedValue |= Param.setAutoSetParams(modelParameter, params, rawInput);

		// It is got and saved to DB in MySQL class.
		if (isTunedValue) {
			rawInput.put(MySQL.tuned_or_defaults, MySQL.tuned);
		}
		else {
			rawInput.put(MySQL.tuned_or_defaults, MySQL.defaults);
		}

		// set response_column param
		modelParameter._response_column = train_dataset.getResponseColumn();

		// set train/validate params
		Frame trainFrame = null;
		Frame validateFrame = null;
		Frame betaConstraints = null;

		try {

			System.out.println("Create train frame: " + train_dataset_id);
			trainFrame = train_dataset.getFrame();

			if (StringUtils.isNotEmpty(validate_dataset_id) && validate_dataset != null
					&& validate_dataset.isAvailabel()) {
				System.out.println("Create validate frame: " + validate_dataset_id);
				validateFrame = validate_dataset.getFrame();
			}

			if (algorithm.equals(FunctionUtils.glm)) {
				betaConstraints = createBetaConstraints(rawInput, trainFrame, train_dataset.getResponseColumn());
				if (betaConstraints != null) {
					((GLMParameters) modelParameter)._beta_constraints = betaConstraints._key;
				}
			}
		}
		catch (Exception e) {
			if (trainFrame != null) {
				trainFrame.remove();
			}
			if (validateFrame != null) {
				validateFrame.remove();
			}
			if (betaConstraints != null) {
				betaConstraints.remove();
			}
			throw e;
		}

		System.out.println("Set train frame");
		modelParameter._train = trainFrame._key;

		if (validateFrame != null) {
			System.out.println("Set validate frame");
			modelParameter._valid = validateFrame._key;
		}

		System.out.println("Create success modelParameter object.");
		return modelParameter;
	}

	/**
	 * If the testcase is negative testcase. The function will compare error message with a message in testcase
	 * 
	 * @param isNegativeTestcase
	 * @param exception
	 * @param rawInput
	 */
	private static void handleTestcaseFailed(boolean isNegativeTestcase, Throwable exception,
			HashMap<String, String> rawInput) {

		System.out.println("Testcase failed");
		exception.printStackTrace();
		if (isNegativeTestcase) {

			System.out.println("This is negative testcase");
			System.out.println("Error message in testcase: " + rawInput.get(CommonHeaders.error_message));
			System.out.println("Error message in program: " + exception.getMessage());

			if (StringUtils.isEmpty(rawInput.get(CommonHeaders.error_message))
					|| !exception.getMessage().contains(rawInput.get(CommonHeaders.error_message))) {
				Assert.fail("Error message do not match with testcase", exception);
			}
			else {
				System.out.println("Error message match with testcase");
			}
		}
		else {
			Assert.fail(exception.getMessage(), exception);
		}
	}

	public static void basicTesting(String algorithm, Model.Parameters parameter, boolean isNegativeTestcase,
			HashMap<String, String> rawInput) {

		boolean isTestSuccessfully = false;
		Frame trainFrame = null;
		Frame score = null;
		Model.Output modelOutput = null;
		ModelMetrics trainingMetrics = null;
		ModelMetrics testMetrics = null;

		DRF drfJob = null;
		DRFModel drfModel = null;

		Key modelKey = Key.make("model");
		GLM glmJob = null;
		GLMModel glmModel = null;
		HashMap<String, Double> coef = null;

		GBM gbmJob = null;
		GBMModel gbmModel = null;

		DeepLearning dlJob = null;
		DeepLearningModel dlModel = null;

		trainFrame = parameter._train.get();

		try {
			Scope.enter();
			double modelStartTime = 0;
			double modelStopTime = 0;
			switch (algorithm) {
				case FunctionUtils.drf:

					System.out.println("Build model");
					drfJob = new DRF((DRFModel.DRFParameters) parameter);

					System.out.println("Train model:");
					modelStartTime = System.currentTimeMillis();
					drfModel = drfJob.trainModel().get();
					modelStopTime = System.currentTimeMillis();

					modelOutput = drfModel._output;
					break;

				case FunctionUtils.glm:

					System.out.println("Build model");
					glmJob = new GLM(modelKey, "basic glm test", (GLMParameters) parameter);

					System.out.println("Train model");
					modelStartTime = System.currentTimeMillis();
					glmModel = glmJob.trainModel().get();
					modelStopTime = System.currentTimeMillis();

					modelOutput = glmModel._output;
					break;

				case FunctionUtils.gbm:

					System.out.println("Build model ");
					gbmJob = new GBM((GBMParameters) parameter);

					System.out.println("Train model");
					modelStartTime = System.currentTimeMillis();
					gbmModel = gbmJob.trainModel().get();
					modelStopTime = System.currentTimeMillis();

					modelOutput = gbmModel._output;
					break;

				case FunctionUtils.dl:

					System.out.println("Build model ");
					dlJob = new DeepLearning((DeepLearningParameters) parameter);

					System.out.println("Train model");
					modelStartTime = System.currentTimeMillis();
					dlModel = dlJob.trainModel().get();
					modelStopTime = System.currentTimeMillis();

					modelOutput = dlModel._output;
					break;
			}

			// check regression/classification
			if (modelOutput.isClassifier() && !Param.parseBoolean(rawInput.get(CommonHeaders.classification))) {
				Assert.fail("This is regression testcase");
			}
			else if (!modelOutput.isClassifier() && Param.parseBoolean(rawInput.get(CommonHeaders.classification))) {
				Assert.fail("This is classification testcase");
			}

			isTestSuccessfully = true;

			// write the MSE/AUC result to log and database
			if (!isNegativeTestcase) {
				System.out.println("Testcase passed.");

				trainingMetrics = modelOutput._training_metrics;
				testMetrics = modelOutput._validation_metrics;

				HashMap<String,Double> train = new HashMap<String,Double>();
				HashMap<String,Double> test = new HashMap<String,Double>();

				train.put("ModelBuildTime", modelStopTime - modelStartTime);

				// Supervised metrics
				train.put("MSE",trainingMetrics.mse());
				test.put("MSE",testMetrics.mse());
				train.put("R2",((ModelMetricsSupervised) trainingMetrics).r2());
				test.put("R2",((ModelMetricsSupervised) testMetrics).r2());

				// Regression metrics
				if( trainingMetrics instanceof ModelMetricsRegression) {
					train.put("MeanResidualDeviance",((ModelMetricsRegression) trainingMetrics)._mean_residual_deviance);
					test.put("MeanResidualDeviance",((ModelMetricsRegression) testMetrics)._mean_residual_deviance);
				}

				// Binomial metrics
				if( trainingMetrics instanceof ModelMetricsBinomial) {
					train.put("AUC",((ModelMetricsBinomial) trainingMetrics).auc());
					test.put("AUC",((ModelMetricsBinomial) testMetrics).auc());
					train.put("Gini",((ModelMetricsBinomial) trainingMetrics)._auc._gini);
					test.put("Gini",((ModelMetricsBinomial) testMetrics)._auc._gini);
					train.put("Logloss",((ModelMetricsBinomial) trainingMetrics).logloss());
					test.put("Logloss",((ModelMetricsBinomial) testMetrics).logloss());
					train.put("F1",((ModelMetricsBinomial) trainingMetrics).cm().F1());
					test.put("F1",((ModelMetricsBinomial) testMetrics).cm().F1());
					train.put("F2",((ModelMetricsBinomial) trainingMetrics).cm().F2());
					test.put("F2",((ModelMetricsBinomial) testMetrics).cm().F2());
					train.put("F0point5",((ModelMetricsBinomial) trainingMetrics).cm().F0point5());
					test.put("F0point5",((ModelMetricsBinomial) testMetrics).cm().F0point5());
					train.put("Accuracy",((ModelMetricsBinomial) trainingMetrics).cm().accuracy());
					test.put("Accuracy",((ModelMetricsBinomial) testMetrics).cm().accuracy());
					train.put("Error",((ModelMetricsBinomial) trainingMetrics).cm().err());
					test.put("Error",((ModelMetricsBinomial) testMetrics).cm().err());
					train.put("Precision",((ModelMetricsBinomial) trainingMetrics).cm().precision());
					test.put("Precision",((ModelMetricsBinomial) testMetrics).cm().precision());
					train.put("Recall",((ModelMetricsBinomial) trainingMetrics).cm().recall());
					test.put("Recall",((ModelMetricsBinomial) testMetrics).cm().recall());
					train.put("MCC",((ModelMetricsBinomial) trainingMetrics).cm().mcc());
					test.put("MCC",((ModelMetricsBinomial) testMetrics).cm().mcc());
					train.put("MaxPerClassError",((ModelMetricsBinomial) trainingMetrics).cm().max_per_class_error());
					test.put("MaxPerClassError",((ModelMetricsBinomial) testMetrics).cm().max_per_class_error());
				}

				// GLM-specific metrics
				if( trainingMetrics instanceof ModelMetricsRegressionGLM) {
					train.put("ResidualDeviance",((ModelMetricsRegressionGLM) trainingMetrics)._resDev);
					test.put("ResidualDeviance",((ModelMetricsRegressionGLM) testMetrics)._resDev);
					train.put("ResidualDegreesOfFreedom",(double)((ModelMetricsRegressionGLM) trainingMetrics)._residualDegressOfFreedom);
					test.put("ResidualDegreesOfFreedom",(double)((ModelMetricsRegressionGLM) testMetrics)._residualDegressOfFreedom);
					train.put("NullDeviance",((ModelMetricsRegressionGLM) trainingMetrics)._nullDev);
					test.put("NullDeviance",((ModelMetricsRegressionGLM) testMetrics)._nullDev);
					train.put("NullDegreesOfFreedom",(double)((ModelMetricsRegressionGLM) trainingMetrics)._nullDegressOfFreedom);
					test.put("NullDegreesOfFreedom",(double)((ModelMetricsRegressionGLM) testMetrics)._nullDegressOfFreedom);
					train.put("AIC",((ModelMetricsRegressionGLM) trainingMetrics)._AIC);
					test.put("AIC",((ModelMetricsRegressionGLM) testMetrics)._AIC);
				}
				if( trainingMetrics instanceof ModelMetricsBinomialGLM) {
					train.put("ResidualDeviance",((ModelMetricsBinomialGLM) trainingMetrics)._resDev);
					test.put("ResidualDeviance",((ModelMetricsBinomialGLM) testMetrics)._resDev);
					train.put("ResidualDegreesOfFreedom",(double)((ModelMetricsBinomialGLM) trainingMetrics)._residualDegressOfFreedom);
					test.put("ResidualDegreesOfFreedom",(double)((ModelMetricsBinomialGLM) testMetrics)._residualDegressOfFreedom);
					train.put("NullDeviance",((ModelMetricsBinomialGLM) trainingMetrics)._nullDev);
					test.put("NullDeviance",((ModelMetricsBinomialGLM) testMetrics)._nullDev);
					train.put("NullDegreesOfFreedom",(double)((ModelMetricsBinomialGLM) trainingMetrics)._nullDegressOfFreedom);
					test.put("NullDegreesOfFreedom",(double)((ModelMetricsBinomialGLM) testMetrics)._nullDegressOfFreedom);
					train.put("AIC",((ModelMetricsBinomialGLM) trainingMetrics)._AIC);
					test.put("AIC",((ModelMetricsBinomialGLM) testMetrics)._AIC);
				}

				MySQL.save(train, test, rawInput);
			}
		}
		catch (Exception ex) {

			handleTestcaseFailed(isNegativeTestcase, ex, rawInput);
		}
		catch (AssertionError ae) {

			handleTestcaseFailed(isNegativeTestcase, ae, rawInput);
		}
		finally {
			if (drfJob != null) {
				drfJob.remove();
			}
			if (drfModel != null) {
				drfModel.delete();
			}
			if (glmJob != null) {
				glmJob.remove();
			}
			if (glmModel != null) {
				glmModel.delete();
			}
			if (gbmJob != null) {
				gbmJob.remove();
			}
			if (gbmModel != null) {
				gbmModel.delete();
			}
			if (dlJob != null) {
				dlJob.remove();
			}
			if (dlModel != null) {
				dlModel.delete();
			}
			if (score != null) {
				score.remove();
				score.delete();
			}
			Scope.exit();
		}

		if (isNegativeTestcase && isTestSuccessfully) {
			System.out.println("It is negative testcase");
			Assert.fail("It is negative testcase");
		}
	}

	// ---------------------------------------------- //
	// initiate data. Read testcase files
	// ---------------------------------------------- //
	public static Object[][] readAllTestcase(HashMap<String, Dataset> dataSetCharacteristic, String algorithm) {

		if (StringUtils.isNotEmpty(algorithm)) {
			return readAllTestcaseOneAlgorithm(dataSetCharacteristic, algorithm);
		}
		Object[][] result = null;
		int nrows = 0;
		int ncols = 0;
		int r = 0;
		int i = 0;

		Object[][] drfTestcase = readAllTestcaseOneAlgorithm(dataSetCharacteristic, FunctionUtils.drf);
		Object[][] gbmTestcase = readAllTestcaseOneAlgorithm(dataSetCharacteristic, FunctionUtils.gbm);
		Object[][] glmTestcase = readAllTestcaseOneAlgorithm(dataSetCharacteristic, FunctionUtils.glm);
		Object[][] dlTestcase = readAllTestcaseOneAlgorithm(dataSetCharacteristic, FunctionUtils.dl);

		if (drfTestcase != null && drfTestcase.length != 0) {
			nrows = drfTestcase[0].length;
			ncols += drfTestcase.length;
		}
		if (gbmTestcase != null && gbmTestcase.length != 0) {
			nrows = gbmTestcase[0].length;
			ncols += gbmTestcase.length;
		}
		if (glmTestcase != null && glmTestcase.length != 0) {
			nrows = glmTestcase[0].length;
			ncols += glmTestcase.length;
		}
		if (dlTestcase != null && dlTestcase.length != 0) {
			nrows = dlTestcase[0].length;
			ncols += dlTestcase.length;
		}

		result = new Object[ncols][nrows];

		if (drfTestcase != null && drfTestcase.length != 0) {
			for (i = 0; i < drfTestcase.length; i++) {
				result[r++] = drfTestcase[i];
			}
		}
		if (gbmTestcase != null && gbmTestcase.length != 0) {
			for (i = 0; i < gbmTestcase.length; i++) {
				result[r++] = gbmTestcase[i];
			}
		}
		if (glmTestcase != null && glmTestcase.length != 0) {
			for (i = 0; i < glmTestcase.length; i++) {
				result[r++] = glmTestcase[i];
			}
		}
		if (dlTestcase != null && dlTestcase.length != 0) {
			for (i = 0; i < dlTestcase.length; i++) {
				result[r++] = dlTestcase[i];
			}
		}
		return result;
	}

	private static Object[][] readAllTestcaseOneAlgorithm(HashMap<String, Dataset> dataSetCharacteristic,
			String algorithm) {

		int indexRowHeader = 0;
		String positiveTestcaseFilePath = null;
		String negativeTestcaseFilePath = null;
		List<String> listHeaders = null;

		switch (algorithm) {
			case FunctionUtils.drf:
				indexRowHeader = DRFConfig.indexRowHeader;
				positiveTestcaseFilePath = DRFConfig.positiveTestcaseFilePath;
				negativeTestcaseFilePath = DRFConfig.negativeTestcaseFilePath;
				listHeaders = DRFConfig.listHeaders;
				break;

			case FunctionUtils.glm:
				indexRowHeader = GLMConfig.indexRowHeader;
				positiveTestcaseFilePath = GLMConfig.positiveTestcaseFilePath;
				negativeTestcaseFilePath = GLMConfig.negativeTestcaseFilePath;
				listHeaders = GLMConfig.listHeaders;
				break;

			case FunctionUtils.gbm:
				indexRowHeader = GBMConfig.indexRowHeader;
				positiveTestcaseFilePath = GBMConfig.positiveTestcaseFilePath;
				negativeTestcaseFilePath = GBMConfig.negativeTestcaseFilePath;
				listHeaders = GBMConfig.listHeaders;
				break;
			case FunctionUtils.dl:
				indexRowHeader = DeepLearningConfig.indexRowHeader;
				positiveTestcaseFilePath = DeepLearningConfig.positiveTestcaseFilePath;
				negativeTestcaseFilePath = DeepLearningConfig.negativeTestcaseFilePath;
				listHeaders = DeepLearningConfig.listHeaders;
				break;

			default:
				System.out.println("do not implement for algorithm: " + algorithm);
				return null;
		}

		Object[][] result = FunctionUtils.dataProvider(dataSetCharacteristic, listHeaders, algorithm,
				positiveTestcaseFilePath, negativeTestcaseFilePath, indexRowHeader);

		return result;
	}

	private static Object[][] dataProvider(HashMap<String, Dataset> dataSetCharacteristic, List<String> listHeaders,
			String algorithm, String positiveTestcaseFilePath, String negativeTestcaseFilePath, int indexRowHeader) {

		Object[][] result = null;
		Object[][] positiveData = null;
		Object[][] negativeData = null;
		int numRow = 0;
		int numCol = 0;
		int r = 0;

		positiveData = readTestcaseFile(dataSetCharacteristic, listHeaders, positiveTestcaseFilePath, indexRowHeader,
				algorithm, false);
		negativeData = readTestcaseFile(dataSetCharacteristic, listHeaders, negativeTestcaseFilePath, indexRowHeader,
				algorithm, true);

		if (positiveData != null && positiveData.length != 0) {
			numRow += positiveData.length;
			numCol = positiveData[0].length;
		}
		if (negativeData != null && negativeData.length != 0) {
			numRow += negativeData.length;
			numCol = negativeData[0].length;
		}

		if (numRow == 0) {
			return null;
		}

		result = new Object[numRow][numCol];

		if (positiveData != null && positiveData.length != 0) {
			for (int i = 0; i < positiveData.length; i++) {
				result[r++] = positiveData[i];
			}
		}
		if (negativeData != null && negativeData.length != 0) {
			for (int i = 0; i < negativeData.length; i++) {
				result[r++] = negativeData[i];
			}
		}

		return result;
	}

	private static Object[][] readTestcaseFile(HashMap<String, Dataset> dataSetCharacteristic,
			List<String> listHeaders, String fileName, int indexRowHeader, String algorithm, boolean isNegativeTestcase) {

		System.out.println("Read testcase: " + fileName);

		Object[][] result = null;
		CSVReader csvReader = null;
		List<String[]> contents = null;
		String[] hearderRow = null;

		if (StringUtils.isEmpty(fileName)) {
			System.out.println("Not found file: " + fileName);
			return null;
		}

		// read data from file
		try {
			csvReader = new CSVReader(new FileReader(TestNGUtil.find_test_file_static(fileName)));
		}
		catch (Exception ignore) {
			System.out.println("Cannot open file: " + fileName);
			ignore.printStackTrace();
			return null;
		}

		// read all content from CSV file
		try {
			contents = csvReader.readAll();
		}
		catch (IOException e) {
			System.out.println("Cannot read content from CSV file");
			e.printStackTrace();
			return null;
		}
		finally {
			try {
				csvReader.close();
			}
			catch (IOException e) {
				System.out.println("Cannot close CSV file");
				e.printStackTrace();
			}
		}

		// remove headers
		contents.removeAll(contents.subList(0, indexRowHeader - 1));

		hearderRow = contents.remove(0);

		if (!validateTestcaseFile(listHeaders, fileName, hearderRow)) {
			System.out.println("Testcase file is wrong format");
			return null;
		}

		result = new Object[contents.size()][9];
		int r = 0;
		for (String[] line : contents) {
			HashMap<String, String> rawInput = parseToHashMap(listHeaders, hearderRow, line);

			result[r][0] = rawInput.get(CommonHeaders.testcase_id);
			result[r][1] = rawInput.get(CommonHeaders.test_description);
			result[r][2] = rawInput.get(CommonHeaders.train_dataset_id);
			result[r][3] = rawInput.get(CommonHeaders.validate_dataset_id);
			result[r][4] = dataSetCharacteristic.get(result[r][2]);
			result[r][5] = dataSetCharacteristic.get(result[r][3]);
			result[r][6] = algorithm;
			result[r][7] = isNegativeTestcase;
			result[r][8] = rawInput;

			r++;
		}

		return result;
	}

	private static boolean validateTestcaseFile(List<String> listHeaders, String fileName, String[] headerRow) {

		System.out.println("validate file: " + fileName);

		for (String header : listHeaders) {
			if (Arrays.asList(headerRow).indexOf(header) < 0) {
				System.out.println(String.format("find not found %s column in %s", header, fileName));
				return false;
			}

		}

		return true;
	}

	private static HashMap<String, String> parseToHashMap(List<String> listHeaders, String[] headerRow,
			String[] testcaseRow) {

		HashMap<String, String> result = new HashMap<String, String>();

		for (String header : listHeaders) {
			result.put(header, testcaseRow[Arrays.asList(headerRow).indexOf(header)]);
		}

		return result;
	}

	public static Object[][] removeAllTestcase(Object[][] testcases, String size) {

		if (testcases == null || testcases.length == 0) {
			return null;
		}

		if (StringUtils.isEmpty(size)) {
			return testcases;
		}

		Object[][] result = null;
		Object[][] temp = null;
		int nrows = 0;
		int ncols = 0;
		int r = 0;
		int i = 0;
		Dataset dataset = null;

		ncols = testcases.length;
		nrows = testcases[0].length;
		temp = new Object[ncols][nrows];

		for (i = 0; i < ncols; i++) {

			dataset = (Dataset) testcases[i][4];

			if (dataset == null) {
				// because we have to show any INVALID testcase thus we have to add this testcase
				temp[r++] = testcases[i];
			}
			else if (size.equals(dataset.getDataSetDirectory())) {
				temp[r++] = testcases[i];
			}
		}

		if (r == 0) {
			System.out.println(String.format("dataset characteristic have no size what is: %s.", size));
		}
		else {

			result = new Object[r][nrows];

			for (i = 0; i < r; i++) {
				result[i] = temp[i];
			}
		}

		return result;
	}

	// ---------------------------------------------- //
	// management dataset characteristic file
	// ---------------------------------------------- //
	public static HashMap<String, Dataset> readDataSetCharacteristic() {

		HashMap<String, Dataset> result = new HashMap<String, Dataset>();
		final String dataSetCharacteristicFilePath = "h2o-testng/src/test/resources/datasetCharacteristics.csv";

		final int numCols = 6;
		final int dataSetId = 0;
		final int dataSetDirectory = 1;
		final int fileName = 2;
		final int responseColumn = 3;
		final int columnNames = 4;
		final int columnTypes = 5;

		File file = null;
		List<String> lines = null;

		try {
			System.out.println("read dataset characteristic");
			file = TestNGUtil.find_test_file_static(dataSetCharacteristicFilePath);
			lines = Files.readAllLines(file.toPath(), Charset.defaultCharset());
		}
		catch (Exception e) {
			System.out.println("Cannot open dataset characteristic file: " + dataSetCharacteristicFilePath);
			e.printStackTrace();
		}

		for (String line : lines) {
			System.out.println("read line: " + line);
			String[] arr = line.trim().split(",", -1);

			if (arr.length < numCols) {
				System.out.println("length of line is short");
			}
			else {
				System.out.println("parse to DataSet object");
				Dataset dataset = new Dataset(arr[dataSetId], arr[dataSetDirectory], arr[fileName],
						arr[responseColumn], arr[columnNames], arr[columnTypes]);

				result.put(arr[dataSetId], dataset);
			}
		}

		return result;
	}

	public static void removeDatasetInDatasetCharacteristic(HashMap<String, Dataset> mapDatasetCharacteristic,
			String dataSetDirectory) {

		if (StringUtils.isEmpty(dataSetDirectory)) {
			return;
		}

		Dataset temp = null;
		for (String key : mapDatasetCharacteristic.keySet()) {

			temp = mapDatasetCharacteristic.get(key);
			if (temp != null && dataSetDirectory.equals(temp.getDataSetDirectory())) {
				temp.closeFrame();
				mapDatasetCharacteristic.remove(key);
			}
		}
	}

	public static void closeAllFrameInDatasetCharacteristic(HashMap<String, Dataset> mapDatasetCharacteristic) {

		for (String key : mapDatasetCharacteristic.keySet()) {

			mapDatasetCharacteristic.get(key).closeFrame();
		}
	}
}
