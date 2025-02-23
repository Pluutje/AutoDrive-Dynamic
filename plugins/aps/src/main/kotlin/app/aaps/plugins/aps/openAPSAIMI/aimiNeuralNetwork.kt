package app.aaps.plugins.aps.openAPSAIMI

import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

class AimiNeuralNetwork(
    private val inputSize: Int,
    private val hiddenSize: Int,
    private val outputSize: Int,
    private val config: TrainingConfig = TrainingConfig(),   // Injection de la config
    private val regularizationLambda: Double = 0.01          // L2 reg (optionnel)
) {

    //----------------------------------------------------------------------------------------------
    // 1) Poids & biais : input->hidden et hidden->output
    //----------------------------------------------------------------------------------------------
    private var weightsInputHidden = Array(inputSize) {
        DoubleArray(hiddenSize) {
            Random.nextDouble(-sqrt(2.0 / inputSize), sqrt(2.0 / inputSize))
        }
    }
    private var biasHidden = DoubleArray(hiddenSize) { 0.01 }

    private var weightsHiddenOutput = Array(hiddenSize) {
        DoubleArray(outputSize) {
            Random.nextDouble(-sqrt(2.0 / hiddenSize), sqrt(2.0 / hiddenSize))
        }
    }
    private var biasOutput = DoubleArray(outputSize) { 0.01 }

    // Historique pour debug ou tracking
    private val trainingLossHistory = mutableListOf<Double>()
    private var bestValLoss = Double.MAX_VALUE  // Pour l'early stopping

    //----------------------------------------------------------------------------------------------
    // 2) Fonctions d'activation & normalisation
    //----------------------------------------------------------------------------------------------

    private fun leakyRelu(x: Double, alpha: Double = config.leakyReluAlpha): Double {
        return if (x >= 0) x else alpha * x
    }

    private fun batchNormalization(values: DoubleArray): DoubleArray {
        val mean = values.average()
        val variance = values.map { (it - mean).pow(2.0) }.average()
        return values.map { (it - mean) / kotlin.math.sqrt(variance + 1e-8) }.toDoubleArray()
    }

    private fun applyDropout(values: DoubleArray, dropoutRate: Double): DoubleArray {
        return values.map { if (Random.nextDouble() < dropoutRate) 0.0 else it }.toDoubleArray()
    }

    //----------------------------------------------------------------------------------------------
    // 3) Forward pass
    //----------------------------------------------------------------------------------------------
    /**
     * @param inferenceMode = true pour la prédiction (pas de dropout)
     */
    private fun forwardPass(
        input: FloatArray,
        inferenceMode: Boolean = false
    ): Pair<DoubleArray, DoubleArray> {
        // Couche cachée
        val hiddenRaw = DoubleArray(hiddenSize) { h ->
            var sum = 0.0
            for (i in input.indices) {
                sum += input[i] * weightsInputHidden[i][h]
            }
            sum + biasHidden[h]
        }

        // Activation LeakyReLU
        val hiddenActivated = hiddenRaw.map { leakyRelu(it) }.toDoubleArray()

        // BatchNorm (optionnel, en mode entraînement uniquement)
        val hiddenNorm = if (!inferenceMode && config.useBatchNorm) {
            batchNormalization(hiddenActivated)
        } else {
            hiddenActivated
        }

        // Dropout (optionnel, en mode entraînement uniquement)
        val hiddenDropped = if (!inferenceMode && config.useDropout) {
            applyDropout(hiddenNorm, config.dropoutRate)
        } else {
            hiddenNorm
        }

        // Couche de sortie
        val output = DoubleArray(outputSize) { o ->
            var sum = 0.0
            for (h in hiddenDropped.indices) {
                sum += hiddenDropped[h] * weightsHiddenOutput[h][o]
            }
            sum + biasOutput[o]
        }

        return hiddenDropped to output
    }

    /**
     * Prévoir/predire la sortie (inférence, donc pas de dropout)
     */
    fun predict(input: FloatArray): DoubleArray {
        return forwardPass(input, inferenceMode = true).second
    }

    //----------------------------------------------------------------------------------------------
    // 4) Loss & régularisation
    //----------------------------------------------------------------------------------------------
    private fun mseLoss(output: DoubleArray, target: DoubleArray): Double {
        val sumSq = output.indices.sumOf { i -> (output[i] - target[i]).pow(2.0) }
        return sumSq / output.size
    }

    private fun l2Regularization(): Double {
        var reg = 0.0
        weightsInputHidden.forEach { row ->
            row.forEach { w -> reg += w.pow(2.0) }
        }
        weightsHiddenOutput.forEach { row ->
            row.forEach { w -> reg += w.pow(2.0) }
        }
        return reg * regularizationLambda
    }

    //----------------------------------------------------------------------------------------------
    // 5) Backpropagation + ADAM
    //----------------------------------------------------------------------------------------------
    /**
     * Calcule les gradients sur weightsInputHidden et weightsHiddenOutput
     */
    private fun backpropagation(input: FloatArray, target: DoubleArray): Pair<Array<DoubleArray>, Array<DoubleArray>> {
        val (hidden, output) = forwardPass(input, inferenceMode = false)

        // dL/dOut
        val gradOutput = DoubleArray(outputSize) { i -> 2.0 * (output[i] - target[i]) }

        // Grad sur la couche hidden->output
        val gradHiddenOutput = Array(hiddenSize) { h ->
            DoubleArray(outputSize) { o ->
                gradOutput[o] * hidden[h]
            }
        }

        // gradHidden : dérivé LeakyReLU
        val gradHidden = DoubleArray(hiddenSize) { h ->
            val sum = gradOutput.indices.sumOf { o -> gradOutput[o] * weightsHiddenOutput[h][o] }
            // LeakyRelu derivative
            if (hidden[h] >= 0) sum else sum * config.leakyReluAlpha
        }

        // Grad sur la couche input->hidden
        val gradInputHidden = Array(inputSize) { i ->
            DoubleArray(hiddenSize) { h ->
                gradHidden[h] * input[i]
            }
        }

        return gradInputHidden to gradHiddenOutput
    }

    // Stockage des moments Adam
    private val mInputHidden = Array(inputSize) { DoubleArray(hiddenSize) { 0.0 } }
    private val vInputHidden = Array(inputSize) { DoubleArray(hiddenSize) { 0.0 } }
    private val mHiddenOutput = Array(hiddenSize) { DoubleArray(outputSize) { 0.0 } }
    private val vHiddenOutput = Array(hiddenSize) { DoubleArray(outputSize) { 0.0 } }
    private var adamStep = 0

    private fun adamUpdate(
        weights: Array<DoubleArray>,
        grads: Array<DoubleArray>,
        m: Array<DoubleArray>,
        v: Array<DoubleArray>
    ) {
        adamStep++
        val beta1 = config.beta1
        val beta2 = config.beta2
        val eps = config.epsilon
        for (i in weights.indices) {
            for (j in weights[i].indices) {
                m[i][j] = beta1 * m[i][j] + (1 - beta1) * grads[i][j]
                v[i][j] = beta2 * v[i][j] + (1 - beta2) * grads[i][j] * grads[i][j]

                val mHat = m[i][j] / (1 - beta1.pow(adamStep.toDouble()))
                val vHat = v[i][j] / (1 - beta2.pow(adamStep.toDouble()))

                weights[i][j] -= config.learningRate * (mHat / (sqrt(vHat) + eps))

                // L2 weight decay (optionnel)
                weights[i][j] -= config.weightDecay * weights[i][j]
            }
        }
    }

    //----------------------------------------------------------------------------------------------
    // 6) Entraînement + early stopping
    //----------------------------------------------------------------------------------------------
    /**
     * Entraîne le réseau en mini-batch, avec validation pour early stopping
     */
    fun trainWithValidation(
        trainInputs: List<FloatArray>,
        trainTargets: List<DoubleArray>,
        valInputs: List<FloatArray>,
        valTargets: List<DoubleArray>
    ) {
        if (trainInputs.isEmpty()) {
            println("No training data - aborting.")
            return
        }

        // Reset de l'historique
        trainingLossHistory.clear()
        bestValLoss = Double.MAX_VALUE
        adamStep = 0

        val totalEpochs = if (config.epochs <= 0) 1000 else config.epochs
        val batchSize = if (config.batchSize <= 0) 32 else config.batchSize
        var epochsWithoutImprovement = 0

        for (epoch in 1..totalEpochs) {
            val indices = trainInputs.indices.shuffled()
            var totalLoss = 0.0

            // mini-batch
            indices.chunked(batchSize).forEach { batchIdx ->
                batchIdx.forEach { idx ->
                    val input = trainInputs[idx]
                    val target = trainTargets[idx]
                    val (gradIH, gradHO) = backpropagation(input, target)

                    // Update input->hidden
                    adamUpdate(weightsInputHidden, gradIH, mInputHidden, vInputHidden)
                    // Update hidden->output
                    adamUpdate(weightsHiddenOutput, gradHO, mHiddenOutput, vHiddenOutput)

                    // recalc pour la loss
                    val out = forwardPass(input, inferenceMode = false).second
                    totalLoss += mseLoss(out, target)
                }
            }

            val avgTrainLoss = totalLoss / trainInputs.size
            trainingLossHistory.add(avgTrainLoss)

            // Validation
            val valLoss = validate(valInputs, valTargets)
            println("Epoch $epoch/$totalEpochs - trainLoss=$avgTrainLoss - valLoss=$valLoss")

            if (valLoss < bestValLoss) {
                bestValLoss = valLoss
                epochsWithoutImprovement = 0
                // Optionnel : sauvegarder les poids
            } else {
                epochsWithoutImprovement++
                if (epochsWithoutImprovement >= config.patience) {
                    println("Early stopping at epoch $epoch (no improvement).")
                    break
                }
            }
        }
    }

    /**
     * Validation = calcule la perte moyenne + L2
     */
    fun validate(valInputs: List<FloatArray>, valTargets: List<DoubleArray>): Double {
        if (valInputs.isEmpty()) return 0.0

        var totalLoss = 0.0
        for (i in valInputs.indices) {
            val out = forwardPass(valInputs[i], inferenceMode = true).second
            totalLoss += mseLoss(out, valTargets[i])
        }
        totalLoss += l2Regularization()
        return totalLoss / valInputs.size
    }

    //----------------------------------------------------------------------------------------------
    // 7) Méthode statique "refineSMB"
    //----------------------------------------------------------------------------------------------
    companion object {
        /**
         * Appelable depuis votre code appelant :
         *   val adjusted = AimiNeuralNetwork.refineSMB(smb, nn, input)
         */
        fun refineSMB(smb: Float, nn: AimiNeuralNetwork, input: DoubleArray?): Float {
            if (input == null) return smb
            val floatInput = input.map { it.toFloat() }.toFloatArray()
            val prediction = nn.predict(floatInput)[0]
            return smb + prediction.toFloat()
        }
    }

    //----------------------------------------------------------------------------------------------
    // 8) Normalisation z-score (optionnelle)
    //----------------------------------------------------------------------------------------------
    fun zScoreNormalization(data: List<FloatArray>): List<FloatArray> {
        if (data.isEmpty()) return emptyList()
        val dim = data.first().size

        val means = DoubleArray(dim) { 0.0 }
        val stdDevs = DoubleArray(dim) { 0.0 }

        data.forEach { row ->
            for (i in row.indices) {
                means[i] += row[i]
                stdDevs[i] += (row[i] * row[i])
            }
        }

        for (i in means.indices) {
            means[i] /= data.size
            stdDevs[i] = kotlin.math.sqrt(stdDevs[i] / data.size - means[i].pow(2.0))
        }

        return data.map { row ->
            FloatArray(dim) { i ->
                if (stdDevs[i] != 0.0) {
                    ((row[i] - means[i]) / stdDevs[i]).toFloat()
                } else {
                    (row[i] - means[i]).toFloat()
                }
            }
        }
    }
}
