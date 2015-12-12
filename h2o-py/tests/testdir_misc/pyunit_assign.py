import sys
sys.path.insert(1, "../../")
import h2o

def pyunit_assign(ip,port):

    pros = h2o.import_frame(h2o.locate("smalldata/prostate/prostate.csv"))
    pq = pros.quantile()

    PSA_outliers = pros[((pros["PSA"] <= pq[1,1]) | (pros["PSA"] >= pq[1,9]))]
    PSA_outliers = h2o.assign(PSA_outliers, "PSA.outliers")
    print(pros)
    print(PSA_outliers)
    assert PSA_outliers.frame_id == "PSA.outliers", "Expected frame id to be PSA.outliers, but got {0}".format(PSA_outliers.frame_id)

if __name__ == "__main__":
    h2o.run_test(sys.argv, pyunit_assign)
