package com.slonic.NeuralNetwork

import breeze.linalg._
import breeze.numerics._
import breeze.stats.distributions.Rand
import scala.annotation.tailrec
import java.io.File

class ANN(inputSize: Int, nHidden: Int = 2, nOutput: Int = 10,
          ep: Double = 0.000001) {

  /* Constants for gradient checking */
  // Layer to check (running on all layers will take too long)
  val LAYER_CHK = 0

  // Number of neurons to check
  val NUM_NODES_CHK = 3

  // Number of weights to check (all of them by default)
  val NUM_WEIGHTS_CHK = 60

  // Acceptable order of difference between gradients
  val EPSILON_CHK = 10e-7

  // Number of nodes in a hidden layer
  val LAYER_SIZE = 25

//  def sigmoid(z: DenseVector[Double]): DenseVector[Double] = {
//    val ones = DenseVector.ones[Double](z.length)
//    ones / (exp(-z) + 1.0)
//  }

  def sigmoidGrad(a: DenseVector[Double]): DenseVector[Double] = a :* (-a + 1.0)

  def createLayer(n: Int): DenseMatrix[Double] = n match {
    // first hidden layer
    case 0 => DenseMatrix.rand[Double](LAYER_SIZE, inputSize + 1, rand = Rand.gaussian)
    // output layer
    case `nHidden` => DenseMatrix.rand[Double](nOutput, LAYER_SIZE + 1, rand = Rand.gaussian)
    // other hidden layers
    case _ => DenseMatrix.rand[Double](LAYER_SIZE, LAYER_SIZE + 1, rand = Rand.gaussian)
  }

  def zeroCopy(mx: Array[DenseMatrix[Double]]) =
    mx.map(m => DenseMatrix.zeros[Double](m.rows, m.cols))

  var weights: Array[DenseMatrix[Double]] = (0 to nHidden).map(createLayer).toArray

  def binarize(ys: DenseVector[Int]): DenseMatrix[Double] = {
    val kl = ys.toArray.toSet
    val ysBin = DenseMatrix.zeros[Double](ys.length, kl.size)
    ys.data.zipWithIndex.foreach{case (y, i) => ysBin.update(i, y - 1, 1.0)}
    ysBin
  }

  def loss(y: DenseVector[Double], yPred: DenseVector[Double]): DenseVector[Double] =
    (-y :* log(yPred)) :- ((-y + 1.0) :* log(-yPred + 1.0))

  def J(x: DenseMatrix[Double], thetas: Array[DenseMatrix[Double]], y: DenseMatrix[Double], regularize: Boolean = true,
        lambda: Double = 0.8): Double = {
    val j = (1.0/x.rows) * sum(for {
      i <- 0 to x.rows - 1
      yPred = h(x(i, ::).inner, thetas)._1
      yi = y(i, ::).inner
    } yield sum(loss(yi, yPred)))

    if(regularize) {
      j + thetas.map(t => sum(sum(t :* t, Axis._0))).sum * (lambda / (2.0 * x.rows))
    } else
      j
  }

 // Forward propagation
  @tailrec
  final def h(x: DenseVector[Double], thetas: Array[DenseMatrix[Double]],
              aVec: List[DenseVector[Double]] = List()): (DenseVector[Double], List[DenseVector[Double]])= {
    if(thetas.isEmpty)
      (x(1 to -1), aVec)
    else {
      val a = sigmoid(thetas.head * x).toArray
      h(DenseVector(1.0 +: a), thetas.tail, aVec :+ x(1 to -1))
    }
  }

//  @tailrec
//  final def h(x: DenseMatrix[Double], thetas: Array[DenseMatrix[Double]]): DenseMatrix[Double]= {
//    if(thetas.isEmpty)
//      x(::, 1 to -1)
//    else {
//      var a: DenseMatrix[Double] = sigmoid(thetas.head * x.t)
//      a = DenseMatrix.horzcat(DenseMatrix.ones[Double](a.rows, 1), a)
//      h(a, thetas.tail)
//    }
//  }

  def backpropagate(train: DenseMatrix[Double], ys: DenseMatrix[Double],
                    weights: Array[DenseMatrix[Double]]): Array[DenseMatrix[Double]] = {

    val D = zeroCopy(weights)
    D.foreach(d => d(::, 0) := 1.0)

    for (i <- 0 to train.rows - 1) {
      var (a, aVec) = h(train(i, ::).inner, weights) // get predictions and activation for every layer
      var delta = a - ys(i, ::).inner // delta(L)

      for (l <- weights.length - 1 to 0 by - 1) {
        a = aVec(l)
        D(l)(::, 0) := D(l)(::, 0) :* delta  // bias deltas
        D(l)(::, 1 to -1) := D(l)(::, 1 to -1) + (delta * a.t) // delta(l+1) * a.t(l)

        delta = (weights(l).t(1 to -1, ::) * delta) :* sigmoidGrad(a) // delta(l) = theta(l) * delta(l+1) * a'
      }
    }

    D.map(_ :/ train.rows.toDouble)
  }

  def train(train: DenseMatrix[Double], y: DenseVector[Int], maxIter: Int = 10000,
            checkGradients: Boolean = false, restoreModel: Boolean = false,
            lambda: Double = 0.8): Unit = {

    val ys = binarize(y)
    val bias = DenseMatrix.ones[Double](train.rows, 1)
    val trainI = DenseMatrix.horzcat(bias, train)

    if(restoreModel)
      weights = readWeights
    var cost = J(trainI, weights, ys, lambda = 0.8)
    var nIter = 0


    // Backpropagation
    println(s"Initial cost $cost")
    while(nIter <= maxIter) {

      val D = backpropagate(trainI, ys, weights)

      // After the first run, compare values with numerically calculated gradients to make sure
      // there are no errors in backprop implementation
      if(nIter == 0 && checkGradients) {
        println(s"Running gradient check for L=$LAYER_CHK")
        val grOk = areGradsOk(trainI, ys, D, LAYER_CHK, NUM_NODES_CHK, NUM_WEIGHTS_CHK)
        if(grOk)
          println("Gradients check: PASSED!")
        else
          println("!!! Gradients check: FAILED!")
      }

      // Update weights
      for (l <- 0 to weights.length - 1) {
        weights(l)(::, 0) := D(l)(::, 0)
        weights(l)(::, 1 to -1) := weights(l)(::, 1 to -1) - D(l)(::, 1 to -1) * lambda
      }

      cost = J(trainI, weights, ys, lambda = 0.8)
      println(s"Cost $cost at epoch $nIter")
      if(nIter % 100 == 0) {
        println(s"Saving the weights at epoch $nIter")
        saveWeights(weights)
      }

      nIter += 1
    }

    saveWeights(weights)
  }

  // Check gradients for a given layer, nodes and weights
  def areGradsOk(trainI: DenseMatrix[Double], ys: DenseMatrix[Double], D: Array[DenseMatrix[Double]],
                 ln: Int, rows: Int, cols: Int): Boolean = {
    val numGrads = numGrad(trainI, ys, ln, (0 to rows).toList, (0 to cols).toList)
    val diff = abs(D(ln)(0 to rows, 0 to cols).t.toDenseVector) - abs(DenseVector(numGrads:_*))
    diff.forall(v => v < EPSILON_CHK)
  }

  // Calculate gradients numerically
  def numGrad(trainI: DenseMatrix[Double], ys: DenseMatrix[Double], l: Int, rows: List[Int], cols: List[Int]): List[Double] = {
    val ws = weights.map(_.copy)

    for {
        row <- rows
        col <- cols
        _ = ws(l).update(row, col, ws(l)(row, col) + ep)
        j1 = J(trainI, ws, ys, regularize = false)

        _ = ws(l).update(row, col, ws(l)(row, col) - 2 * ep)
        j2 = J(trainI, ws, ys, regularize = false)

        _ = ws(l).update(row, col, ws(l)(row, col) + ep) // restoring the value
    } yield (j1 - j2) / (2 * ep)

  }

  def saveWeights(weights: Array[DenseMatrix[Double]]) =
    weights.zipWithIndex.foreach{case (ws, i) =>  csvwrite(new File(s"models/theta${i+1}.csv"), ws)}

  def readWeights: Array[DenseMatrix[Double]] =
    (0 to nHidden).map(i => csvread(new File(s"models/theta${i+1}.csv"))).toArray


  def predictProba(test: DenseMatrix[Double]): DenseMatrix[Double] =
    h(test, weights)

//  def predict(test: DenseMatrix[Double]): List[Int] = {
//    val yPred = h(test, weights)
//    for {
//      i <- 0 to yPred.rows - 1
//      y = yPred(i,::).inner
//    } yield y.findAll(_ == max(y))
//  }



}
