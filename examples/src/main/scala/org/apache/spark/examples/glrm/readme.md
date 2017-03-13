# SparkGLRM

SparkGLRM is a Spark package for modeling and fitting generalized low rank models (GLRMs).
GLRMs model a matrix by two low rank matrices, and
include many well known models in machine learning, such as 
principal components analysis (PCA), matrix completion, robust PCA,
nonnegative matrix factorization, k-means, and many more.

For more information on GLRMs, see [our paper](http://arxiv.org/abs/1410.0342).

SparkGLRM makes it easy to mix and match loss functions and regularizers
to construct a model suitable for a particular data set.
In particular, it supports 

* using different loss functions for different entries of the matrix, 
  which is useful when data types are heterogeneous
* fitting the model to only *some* of the entries in the table, 
  which is useful for data tables with many missing (unobserved) entries
* custom regularization of the model

## Design

The matrix to be factored is split entry-wise across many machines. 
The model (factors `X` and `Y`) is repeated and held in memory on every machine. 
Thus the total computation time required to fit the model is proportional to 
the number of non-zeros divided by the number of cores, 
with the restriction that the model should fit in memory on a single machine.
Where possible, hardware acceleration is used for local linear algebraic operations, 
via breeze. 

At every iteration, the current model is broadcast to all machines, 
such that there is only one copy of the model on each machine. 
This particularly important in machines with many cores, 
because it avoids duplicating the model those machines.
Each core on a machine will process a partition of the input matrix, 
using the local copy of the model available.


## Compilation

To compile and run, run the following from the Spark root directory. Compilation:
```
sbt/sbt assembly
```
To run with 4GB of ram:
```
./bin/spark-submit --class org.apache.spark.examples.SparkGLRM  \
  ./examples/target/scala-2.10/spark-examples-1.1.0-SNAPSHOT-hadoop1.0.4.jar \
  --executor-memory 4G \
  --driver-memory 4G
```

# Generalized Low Rank Models

GLRMs form a low rank model for a matrix `A` with `M` rows and `U` columns, 
which can be input as an RDD of non-zero entries.
It is fine if only some of the entries have been observed 
(i.e., the others are missing); the GLRM will only be fit on the observed entries.
The desired model is specified by choosing a rank `rank` for the model,
a loss function `lossGrad`, and two regularizers, `moviesProx` and `usersProx`.
The data is modeled as `XY`, where `X` is a `M`x`rank` matrix and `Y` is a `k`x`rank` matrix.
`X` and `Y` are found by solving the optimization problem

	minimize sum_{(i,j) in A} loss(i, j, X[i,:] Y[:,j], A[i,j]) + sum_i moviesProx(X[i,:]) + sum_j usersProx(Y[:,j])

To fit a GLRM in SparkGLRM, the user specifies

* the data `A` via observed entries
* the dimensions `M` and `U`
* the loss function (sub)gradient `lossGrad`, indexed by `i` and `j`
* the regularizers `moviesProx` and `usersProx`
* the rank `rank`

There are currently several features implemented, including:

* L1 loss `lossL1Grad`
* L2squared loss `lossL2squaredGrad`
* mixedLoss `mixedLossGrad`, for demonstration of per-entry loss function
* quadratic regularization `proxL2`
* L1 regularization `proxL1`
* nonnegative constraint `proxNonneg`

Users may also implement their own losses and regularizers; 
see `SparkGLRM.scala` for more details.

For example, the following code builds parameters and observed entries, in preparation to
fit a model:

	    // Number of movies
        val M = 1000
        // Number of users
        val U = 1000
        // Number of non-zeros per row
        val NNZ = 10
        // Number of features
        val rank = 5
        // Number of iterations
        val numIterations = 100
        // regularization parameter
        val regPen = 0.1
    
    
        // Number of partitions for data, set to number of cores in cluster
        val numChunks = 4
        // Build non-zeros
        val A = sc.parallelize(0 until M, numChunks).flatMap{i =>
          val inds = new scala.collection.mutable.TreeSet[Int]()
          while (inds.size < NNZ) {
            inds += scala.util.Random.nextInt(U)
          }
          inds.toArray.map(j => (i, j, scala.math.random))
        }

To fit the model with squared error loss and quadratic
regularization with `rank=5` on the matrix `A`, call:

        val (ms, us) = fitGLRM(A, M, U, lossL2squaredGrad, proxL2, proxL2, rank, numIterations, regPen)

which runs an alternating directions proximal gradient method on to find the 
`ms` and `us` minimizing the objective function.
To see how well the model performs using RMSE:

    // Output RMSE using learned model
    val finalRMSE = math.sqrt(A.map { case (i, j, rij) =>
      val err = ms(i).dot(us(j)) - rij
      err * err
    }.mean())

## Missing data

The input is a sparse matrix, and so missing data are simply left out of the sparse representation.

## Optimization

### Algorithm
The algorithm implemented is proximal gradient, described as follows, with more details in 
[our paper](http://arxiv.org/abs/1410.0342).

<img src="glrm1.png" width="500">

With `update` implemented as

<img src="glrm3.png" width="250">
 
<img src="glrm2.png" width="250">


### Convergence

The function `fitGLRM` uses an alternating directions proximal gradient method
to minimize the objective. This method is *not* guaranteed to converge to 
the optimum, or even to a local minimum. If your code is not converging
or is converging to a model you dislike, there are a number of parameters you can tweak.

The algorithm starts with `X` and `Y` initialized randomly.
If you have a good guess for a model, try setting them explicitly.
If you think that you're getting stuck in a local minimum, try reinitializing your
GLRM (so as to construct a new initial random point) and see if the model you obtain improves.
 

The step size controls the speed of convergence. Small step sizes will slow convergence,
while large ones will cause divergence.
