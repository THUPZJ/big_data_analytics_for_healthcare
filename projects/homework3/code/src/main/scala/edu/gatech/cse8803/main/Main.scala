/**
 * @author Hang Su <hangsu@gatech.edu>.
 */

package edu.gatech.cse8803.main

import java.text.SimpleDateFormat

import edu.gatech.cse8803.clustering.{NMF, Metrics}
import edu.gatech.cse8803.features.FeatureConstruction
import edu.gatech.cse8803.ioutils.CSVUtils
import edu.gatech.cse8803.model.{Diagnostic, LabResult, Medication}
import edu.gatech.cse8803.phenotyping.T2dmPhenotype
import org.apache.spark.SparkContext._
import org.apache.spark.mllib.clustering.{GaussianMixture, KMeans}
import org.apache.spark.mllib.linalg.{DenseMatrix, Matrices, Vectors, Vector}
import org.apache.spark.mllib.feature.StandardScaler
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.SQLContext
import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.storage.StorageLevel

import scala.io.Source


object Main {
  def main(args: Array[String]) {
    import org.apache.log4j.Logger
    import org.apache.log4j.Level

    Logger.getLogger("org").setLevel(Level.WARN)
    Logger.getLogger("akka").setLevel(Level.WARN)

    val sc = createContext
    sc.getConf.getAll.foreach(println)
    val sqlContext = new SQLContext(sc)

    /** initialize loading of data */
    val (medication, labResult, diagnostic) = loadRddRawData(sqlContext)
    val (candidateMedication, candidateLab, candidateDiagnostic) = loadLocalRawData

    /** conduct phenotyping */
    println("Phenotyping...")
    val phenotypeLabel = T2dmPhenotype.transform(medication, labResult, diagnostic).cache()
    //println(phenotypeLabel.count())

    /** feature construction with all features */
    println("Feature Construction...")
    val featureTuples = sc.union(
      FeatureConstruction.constructDiagnosticFeatureTuple(diagnostic).cache(),
      FeatureConstruction.constructLabFeatureTuple(labResult).cache(),
      FeatureConstruction.constructMedicationFeatureTuple(medication).cache()
    )
    //println(featureTuples.count())

    val rawFeatures = FeatureConstruction.construct(sc, featureTuples).cache()

    //println("Test Clustering...")
    val (kMeansPurity, gaussianMixturePurity, nmfPurity) = testClustering(phenotypeLabel, rawFeatures)
    println(f"[All feature] purity of kMeans is: $kMeansPurity%.5f")
    println(f"[All feature] purity of GMM is: $gaussianMixturePurity%.5f")
    println(f"[All feature] purity of NMF is: $nmfPurity%.5f")

    /** feature construction with filtered features */
    val filteredFeatureTuples = sc.union(
      FeatureConstruction.constructDiagnosticFeatureTuple(diagnostic, candidateDiagnostic),
      FeatureConstruction.constructLabFeatureTuple(labResult, candidateLab),
      FeatureConstruction.constructMedicationFeatureTuple(medication, candidateMedication)
    )

    val filteredRawFeatures = FeatureConstruction.construct(sc, filteredFeatureTuples).cache()

    println("Test Clustering...")
    val (kMeansPurity2, gaussianMixturePurity2, nmfPurity2) = testClustering(phenotypeLabel, filteredRawFeatures)
    println(f"[Filtered feature] purity of kMeans is: $kMeansPurity2%.5f")
    println(f"[Filtered feature] purity of GMM is: $gaussianMixturePurity2%.5f")
    println(f"[Filtered feature] purity of NMF is: $nmfPurity2%.5f")
    sc.stop
  }

  def testClustering(phenotypeLabel: RDD[(String, Int)], rawFeatures:RDD[(String, Vector)]): (Double, Double, Double) = {
    import org.apache.spark.mllib.linalg.Matrix
    import org.apache.spark.mllib.linalg.distributed.RowMatrix

    /** scale features */
    val scaler = new StandardScaler(withMean = true, withStd = true).fit(rawFeatures.map(_._2))
    val features = rawFeatures.map({ case (patientID, featureVector) => (patientID, scaler.transform(Vectors.dense(featureVector.toArray)))})
    val rawFeatureVectors = features.map(_._2).cache()

    /** reduce dimension */
    val mat: RowMatrix = new RowMatrix(rawFeatureVectors)
    val pc: Matrix = mat.computePrincipalComponents(10) // Principal components are stored in a local dense matrix.
    val featureVectors = mat.multiply(pc).rows.cache()

    val densePc = Matrices.dense(pc.numRows, pc.numCols, pc.toArray).asInstanceOf[DenseMatrix]
    /** transform a feature into its reduced dimension representation */
    def transform(feature: Vector): Vector = {
      Vectors.dense(Matrices.dense(1, feature.size, feature.toArray).multiply(densePc).toArray)
    }

    /** TODO: K Means Clustering using spark mllib
      *  Train a k means model using the variabe featureVectors as input
      *  Set maxIterations =20 and seed as 8803L
      *  Assign each feature vector to a cluster(predicted Class)
      *  Obtain an RDD[(Int, Int)] of the form (cluster number, RealClass)
      *  Find Purity using that RDD as an input to Metrics.purity
      *  Remove the placeholder below after your implementation
      **/

    val labels = phenotypeLabel
      .map( _._2)
      .zipWithIndex
      .map( x => (x._2, x._1))
      .cache()

    val k = 3;
    //println("Labels:")
    //println(labels.count())
    //val km_model = KMeans.train(featureVectors, 3, 20)
    //val k_clusters = km_model.predict(featureVectors)
    //println("KMeans")
    val kmeans = new KMeans()
      .setK(k)
      .setMaxIterations(20)
      .setSeed(8803L)

    val k_model = kmeans.run(featureVectors)
    val k_clusters = k_model.predict(featureVectors)
      .zipWithIndex
      .map( x => (x._2, x._1))

    //println("K_Clusters:")
    //println(k_clusters.count())


    // use zip before passing to purity
    println("Purity Calc")
    val toPurity = labels.join(k_clusters).map(_._2)
    //println(toPurity.count())
    println(toPurity.first())
    //

    var kTable = toPurity
      .filter( x => x._2 == 0 )
      .map( x => x._1)
      .countByValue()

    println("KTable::::::::::::::::::::::::")
    println(kTable.toString)

    kTable = toPurity
      .filter( x => x._2 == 1 )
      .map( x => x._1)
      .countByValue()
    println(kTable.toString)

    kTable = toPurity
      .filter( x => x._2 == 2 )
      .map( x => x._1)
      .countByValue()
    println(kTable.toString)

    val kMeansPurity = Metrics.purity(toPurity)


    /** TODO: GMMM Clustering using spark mllib
      *  Train a Gaussian Mixture model using the variabe featureVectors as input
      *  Set maxIterations =20 and seed as 8803L
      *  Assign each feature vector to a cluster(predicted Class)
      *  Obtain an RDD[(Int, Int)] of the form (cluster number, RealClass)
      *  Find Purity using that RDD as an input to Metrics.purity
      *  Remove the placeholder below after your implementation
      **/
    //println("GMM")
    val gmm = new GaussianMixture()
      .setK(k)
      .setMaxIterations(20)
      .setSeed(8803L)

    val gmm_model = gmm.run(featureVectors)
    val g_clusters = gmm_model.predict(featureVectors)
      .zipWithIndex
      .map( x => (x._2, x._1))

    //println("G_Clusters:")
    //println(g_clusters.count())


    // use zip before passing to purity
    //println("Purity Calc")
    val toPurityG = labels.join(g_clusters).map(_._2)
    //println(toPurityG.count())
    //println(toPurityG.first())
    var gTable = toPurityG
      .filter( x => x._2 == 0 )
      .map( x => x._1)
      .countByValue()

    println("GTable::::::::::::::::::::::::")
    println(gTable.toString)

    gTable = toPurityG
      .filter( x => x._2 == 1 )
      .map( x => x._1)
      .countByValue()
    println(gTable.toString)

    gTable = toPurityG
      .filter( x => x._2 == 2 )
      .map( x => x._1)
      .countByValue()
    println(gTable.toString)

    val gaussianMixturePurity = Metrics.purity(toPurityG)

    println("NMF")
    /** NMF */
    val rawFeaturesNonnegative = rawFeatures.map({ case (patientID, f)=> Vectors.dense(f.toArray.map(v=>Math.abs(v)))})
    val (w, _) = NMF.run(new RowMatrix(rawFeaturesNonnegative), 3, 100)
    // for each row (patient) in W matrix, the index with the max value should be assigned as its cluster type
    val assignments = w.rows.map(_.toArray.zipWithIndex.maxBy(_._1)._2)
    // zip patientIDs with their corresponding cluster assignments
    // Note that map doesn't change the order of rows
    val assignmentsWithPatientIds=features.map({case (patientId,f)=>patientId}).zip(assignments)
    // join your cluster assignments and phenotypeLabel on the patientID and obtain a RDD[(Int,Int)]
    // which is a RDD of (clusterNumber, phenotypeLabel) pairs
    val nmfClusterAssignmentAndLabel = assignmentsWithPatientIds.join(phenotypeLabel).map({case (patientID,value)=>value})
    // Obtain purity value
    var nmfTable = nmfClusterAssignmentAndLabel
      .filter( x => x._1 == 0 )
      .map( x => x._2)
      .countByValue()

    println("NMF Table::::::::::::::::::::::::")
    println(nmfTable.toString)

    nmfTable = nmfClusterAssignmentAndLabel
      .filter( x => x._1 == 1 )
      .map( x => x._2)
      .countByValue()
    println(nmfTable.toString)

    nmfTable = nmfClusterAssignmentAndLabel
      .filter( x => x._1 == 2 )
      .map( x => x._2)
      .countByValue()
    println(nmfTable.toString)

    val nmfPurity = Metrics.purity(nmfClusterAssignmentAndLabel)

    (kMeansPurity, gaussianMixturePurity, nmfPurity)
  }

  /**
   * load the sets of string for filtering of medication
   * lab result and diagnostics
    *
    * @return
   */
  def loadLocalRawData: (Set[String], Set[String], Set[String]) = {
    val candidateMedication = Source.fromFile("data/med_filter.txt").getLines().map(_.toLowerCase).toSet[String]
    val candidateLab = Source.fromFile("data/lab_filter.txt").getLines().map(_.toLowerCase).toSet[String]
    val candidateDiagnostic = Source.fromFile("data/icd9_filter.txt").getLines().map(_.toLowerCase).toSet[String]
    (candidateMedication, candidateLab, candidateDiagnostic)
  }

  def loadRddRawData(sqlContext: SQLContext): (RDD[Medication], RDD[LabResult], RDD[Diagnostic]) = {
    /** You may need to use this date format. */
    val dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssX")

    /** load data using Spark SQL into three RDDs and return them
     * Hint: You can utilize edu.gatech.cse8803.ioutils.CSVUtils and SQLContext.
     *
     * Notes:Refer to model/models.scala for the shape of Medication, LabResult, Diagnostic data type.
     *       Be careful when you deal with String and numbers in String type.
     *       Ignore lab results with missing (empty or NaN) values when these are read in.
     *       For dates, use Date_Resulted for labResults and Order_Date for medication.
     * */

    val med_df = CSVUtils.loadCSVAsTable(sqlContext,
      "data/medication_orders_INPUT.csv", "Medication")

    val med_selection = med_df.select("Member_ID", "Order_Date", "Drug_Name")
    val medication: RDD[Medication] = med_selection.map( x => Medication(x.getString(0),
      dateFormat.parse(x.getString(1)),
      x.getString(2))).persist(StorageLevel.DISK_ONLY)

    medication.count()
    med_df.unpersist()
    med_selection.unpersist()



    val lab_df = CSVUtils.loadCSVAsTable(sqlContext,
      "data/lab_results_INPUT.csv", "LabResult")
    val lab_1 = lab_df.select("Member_ID", "Date_Resulted", "Result_Name", "Numeric_Result")
    val lab_2 = lab_1.withColumn("numTmp", lab_1("Numeric_Result").cast("double"))
      .drop("Numeric_Result")
      .withColumnRenamed("numTmp", "Numeric_Result")

    val lab_3 = lab_2.na.drop()
    val labResult: RDD[LabResult] = lab_3.map( x => LabResult(x.getString(0),
      dateFormat.parse(x.getString(1)),
      x.getString(2),
      x.getDouble(3))).persist(StorageLevel.DISK_ONLY)

    labResult.count()

    lab_df.unpersist()
    lab_1.unpersist()
    lab_2.unpersist()
    lab_3.unpersist()

    val diag_df = CSVUtils.loadCSVAsTable(sqlContext,
    "data/encounter_dx_INPUT.csv", "Diagnostic")//.sample(true, .01)

    val diagIDs = diag_df.select("Encounter_ID").collect.toSet
    val event_df = CSVUtils.loadCSVAsTable(sqlContext,
    "data/encounter_INPUT.csv", "Diagnostic").select("Encounter_ID", "Member_ID", "Encounter_DateTime")//.sample(true, .01)
    val full_df = diag_df.join(event_df, diag_df.col("Encounter_ID").equalTo(event_df("Encounter_ID")))
    val full_selection = full_df.select("Member_ID", "Encounter_DateTime", "code").na.drop()

    val diagnostic: RDD[Diagnostic] = full_selection.map( x => Diagnostic(x.getString(0),
      dateFormat.parse(x.getString(1)),
      x.getString(2))).persist(StorageLevel.DISK_ONLY)

    diagnostic.count()
    diag_df.unpersist()
    event_df.unpersist()
    full_df.unpersist()
    full_selection.unpersist()

    (medication, labResult, diagnostic)
  }

  def createContext(appName: String, masterUrl: String): SparkContext = {
    val conf = new SparkConf()
      .setAppName(appName)
      .setMaster(masterUrl)
      .set("spark.driver.memory", "28g")
      //.set("spark.executor.memory", "6g")
      //.set("spark.driver.maxResultSize", "2g")
      .set("spark.memory.storageFraction", "0.75")
      .set("spark.default.parallelism", "30")
      .set("spark.local.dir", "/home/jeff/tmp")
      .set("spark.shuffle.file.buffer", "500k")
      .set("spark.cores.max", "32")

    new SparkContext(conf)
  }

  def createContext(appName: String): SparkContext = createContext(appName, "local[8]")

  def createContext: SparkContext = createContext("CSE 8803 Homework Two Application", "local[8]")
}
