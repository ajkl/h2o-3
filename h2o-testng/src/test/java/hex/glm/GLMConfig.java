package hex.glm;

import h2o.testng.utils.FunctionUtils;
import h2o.testng.utils.OptionsGroupParam;
import h2o.testng.utils.Param;
import hex.glm.GLMModel.GLMParameters.Family;
import hex.glm.GLMModel.GLMParameters.Solver;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class GLMConfig {

	/**
	 * The first row of data is used to testing.
	 */
	public final static int firstRow = 4;
	public final static String positiveTestcaseFilePath = "h2o-testng/src/test/resources/glmCases.csv";
	public final static String negativeTestcaseFilePath = "h2o-testng/src/test/resources/glmNegCases.csv";
	
	// below are single param which can set automatically to a GLM Object
	public static Param[] params = new Param[] {
			
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
	};
	
	public static OptionsGroupParam familyOptionsParams = new OptionsGroupParam(
			new String[] {"gaussian", "binomial", "poisson", "gamma", "tweedie"},
			new Object[] {Family.gaussian, Family.binomial, Family.poisson, Family.gamma, Family.tweedie}
	); 
	
	public static OptionsGroupParam solverOptionsParams = new OptionsGroupParam(
			new String[] {"auto","irlsm", "lbfgs"},
			new Object[] {Solver.AUTO, Solver.IRLSM, Solver.L_BFGS}
	); 

	public static List<String> tcHeaders = new ArrayList<String>(Arrays.asList(
			"0",
			FunctionUtils.test_description,
			FunctionUtils.testcase_id,

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
			FunctionUtils.train_dataset_id,
			FunctionUtils.validate_dataset_id,

			"_ignored_columns",
			"r",
			"scikit"
	));
}
