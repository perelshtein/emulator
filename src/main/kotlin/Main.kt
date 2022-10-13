package com.github.perelshtein.emulator

import java.io.File
import java.io.IOException
import java.math.RoundingMode
import java.security.InvalidParameterException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.io.path.createDirectory
import java.io.File.separator as SEPARATOR

//эти параметры остаются неизменными для всех комбинаций настроек стратегии
var commissionLimit = ParamDouble()
var commissionMarket = ParamDouble()
var showLast = ParamInt()
var saveLast = ParamInt()
const val depo = 100000.0
lateinit var paramArray: MutableList<Param>

fun main(args: Array<String>) {
    //разбираем параметры командной строки
    var inputName = ""
    var outputName = ""
    var stop = ParamDouble()
    var trailingStop = ParamDouble()
    var safeStop = ParamDouble()
    var takeProfit = ParamDouble()
    var entryFilter = ParamDouble()
    var partProfit = ParamDouble()
    var partFix = ParamDouble()
    var entryWait = ParamInt()

    val paramsIt = args.toList().listIterator()

    try {
         while(paramsIt.hasNext()) {
            val it = paramsIt.next()
            when (it.trimStart('-')) {
                "stop" -> stop.read(paramsIt)
                "trailing-stop" -> trailingStop.read(paramsIt)
                "entry-filter" -> entryFilter.read(paramsIt)
                "safe-stop" -> safeStop.read(paramsIt)
                "take-profit" -> takeProfit.read(paramsIt)
                "entry-wait" -> entryWait.read(paramsIt)
                "commission-limit" -> commissionLimit.read(paramsIt)
                "commission-market" -> commissionMarket.read(paramsIt)
                "show-last" -> showLast.read(paramsIt)
                "save-last" -> saveLast.read(paramsIt)
                "part-profit" -> partProfit.read(paramsIt)
                "part-fix" -> partFix.read(paramsIt)
                // имена файлов?
                else -> {
                    if (paramsIt.hasNext()) inputName = it
                    else if (!paramsIt.hasNext()) outputName = it
                }
            }
        }
        paramArray = mutableListOf(stop, trailingStop, safeStop, takeProfit, entryFilter, entryWait, partProfit, partFix)
        paramArray.removeIf { it.name.isEmpty() }
    }
    catch(e: InvalidParameterException) {
        println("Ошибка: не указано значение параметра ${args[paramsIt.nextIndex() - 1]}\n")
        return
    }

    if(inputName.isEmpty() or outputName.isEmpty()) {
        println("Эмулятор сделок.\n" +
                "Внимание: не указаны файлы для расчета.\n\n" +
                "Использование: emulator [настройки] входной_файл.csv выходной_файл.csv\n" +
                "Разделитель: точка с запятой\n" +
                "Для настроек доступен перебор наиболее прибыльных вариантов.\n" +
                "После нужной опции можно указать одно число (фикс. параметр)\n" +
                "или 3 числа - минимум, максимум и шаг\n\n" +

                "Настройки:\n" +
                "-stop 1.5 или -stop мин макс шаг\n" +
                "   уровень стоп-лосса, в процентах\n" +
                "   или -stop 1 5 0.5 - перебор вариантов от 1 до 5% с шагом 0.5%\n\n" +

                "-trailing-stop 3.0 или -trailing-stop мин макс шаг\n" +
                "   передвигать стоп в безубыток, например\n" +
                "   на каждые 3% движения цены - +0.15% от цены открытия или предыдущего стопа\n\n" +

                "-entry-filter 1.0 или -entry-filter мин макс шаг\n" +
                "   не открывать сделку, пока цена не сдвинется на 1% в нужную сторону\n\n" +

                "-entry-wait 3 или -entry-wait мин макс шаг\n" +
                "   ждать 3 свечи, пока цена не сдвинется в нужную сторону\n" +
                "   1 - свеча, на которой произошел вход в сделку\n\n" +

                "-commission-limit 0.01\n" +
                "   комиссия по лимитной сделке, в процентах\n\n" +

                "-commission-market 0.05\n" +
                "   комиссия по рыночной сделке, в процентах\n\n" +

                "-take-profit 3 или -take-profit мин макс шаг\n" +
                "   уровень прибыли в процентах, при котором сделка будет закрыта\n\n" +

                "-safe-stop 3 или -safe-stop мин макс шаг\n" +
                "   переместить стоп в безубыток, если цена выросла на 3%\n" +
                "   по типу -trailing-stop, но срабатывает один раз\n\n" +

                "-show-last 5\n" +
                "   если использовался перебор вариантов,\n" +
                "   вывести на экран 5 наиболее успешных\n\n" +

                "-save-last 3\n" +
                "   сохранить 3 наиболее успешных варианта\n" +
                "   в отдельную папку.\n\n" +

                "-part-profit 2\n" +
                "   частичный take-profit. Движение цены, в процентах.\n" +
                "   если цена сдвинулась на 2%, закрыть часть позиции\n\n" +

                "-part-fix 20\n" +
                "   частичный take-profit. Размер позиции, в процентах.\n" +
                "   если цена сдвинулась на +2%, зафиксировать прибыль в 20% от всей суммы\n\n" +

                "(C) 2022 http://github.com/perelshtein\n")
    }

    else if(paramArray.any{it.name == "safe-stop"} && paramArray.any{it.name == "trailing-stop"}) {
        println("Ошибка: выберите один параметр, trailing-stop или safe-stop\n" +
                "Они не могут работать одновременно\n")
    }

    else if((paramArray.any{it.name == "part-profit"} || paramArray.any{it.name == "part-fix"})
        && paramArray.any{it.name == "take-profit"}) {
        println("Ошибка: выберите один параметр, part-profit или take-profit\n" +
                "Они не могут работать одновременно\n")
    }

    else {
        var savedCnt = 0
        var lineCnt = 0
        var dealList = mutableListOf<Deal>()
        val candleList = mutableListOf<Candle>()

        //читаем построчно входной файл, кроме заголовка
        try {
            var i = 0

            File(inputName).forEachLine {
                var deal = mutableListOf<String>()
                it.split(";").forEach { deal.add(it.trim(';')) }

                //проверяем заголовок
                if(i == 0) {
                    if(deal.size < 7 || !deal[0].equals("time") || !deal[1].equals("open") ||
                        !deal[2].equals("high") || !deal[3].equals("low") || !deal[4].equals("close") ||
                        !deal[5].equals("Buy") || !deal[6].equals("Sell")) {
                        throw IncorrectInputException("")
                    }
                }
                else {
                    if(deal.size < 7) {
                        println("Пропуск строки номер ${lineCnt + 1}: указано менее 7 значений")
                    }
                    else {
                        val dateParsed = ZonedDateTime.parse(deal[0])

                        //читаем свечу
                        candleList.add(Candle(dateParsed, deal[1].toDouble(), deal[2].toDouble(),
                            deal[3].toDouble(), deal[4].toDouble()))

                        //есть сигнал на покупку?
                        //берем цену закрытия
                        if(!deal[5].toDouble().isNaN()) {
                            dealList.add(Deal(dateParsed, deal[4].toDouble(),true, 0.0, ""))
                            savedCnt++
                        }
                        //или есть сигнал на продажу?
                        //берем цену закрытия
                        else if(!deal[6].toDouble().isNaN()) {
                            dealList.add(Deal(dateParsed, deal[4].toDouble(), false, 0.0, ""))
                            savedCnt++
                        }
                    }
                }
                i++
                lineCnt++
            }
        }

        catch(e: IOException) {
            println("Ошибка чтения файла $inputName")
            return
        }

        catch(e: DateTimeParseException) {
            println("Прекращаю работу: ошибка разбора даты, строка номер ${lineCnt + 1}")
            return
        }

        catch(e: NumberFormatException) {
            println("Прекращаю работу: ошибка разбора строки номер ${lineCnt + 1}")
            return
        }

        catch(e: IncorrectInputException) {
            println("Ошибка: неправильный заголовок входного файла.\n" +
                    "Требуется: time, open, high, low, close, Buy, Sell\n")
            return
        }

        //хотя бы один параметр указан с перебором?
        var multiParamCnt = 0
        var offset = 0
        paramArray.forEach {
            if(it.isMulti) {
                multiParamCnt++
                it.start = offset + 1
                //конец диапазона = (макс - мин + шаг) / шаг + смещение
                if(it.get(1) is Int) it.end = (it.get(1) as Int - it.get(0) as Int + it.get(2) as Int) / it.get(2) as Int + offset
                else if(it.get(1) is Double) it.end = ((it.get(1) as Double - it.get(0) as Double + it.get(2) as Double) / it.get(2) as Double).toInt() + offset
                //смещение для след параметра += конец диапазона
                offset = it.end
            }
        }

        //нет, все просто
        val trade = Trade(candleList, dealList)
        if(multiParamCnt == 0) {
            trade.isSaveDeals = true
            trade.processData()

            if (saveFile(outputName, trade.dealsOutput)
                && saveMonths("month.csv", trade.profitDealsCnt, trade.lossDealsCnt, trade.profit,
                    trade.monthResults)) {
                println(
                    "Все отлично!\n" +
                            "Сохранено сделок: ${trade.dealsOutput.size}\n"
                )
            }
        }

        //нужен перебор
        else {
            val comb = Combinations(offset, multiParamCnt)
            //из полученных вариантов оставляем только те, которые из разных диапазонов
            var arr = mutableListOf<IntArray>()
            while(comb.hasNext()) {
                var tmp = comb.next()
                if(decodeParams(tmp)) arr.add(tmp.copyOf())
            }

            println("Перебор параметров стратегии. ${arr.size} вариантов\n" +
                    "Ctrl+C - прервать расчет\n")
            var preResults = mutableListOf<PreResult>()
            arr.forEach {
                //найдем, какое число какому параметру соотв
                decodeParams(it)

                //запустим расчет с выбранными параметрами
                trade.processData()
                preResults.add(PreResult(trade.profit, it))
            }

            //выбираем 5 или N самых удачных результатов
            if(showLast.get(0) == 0) {
                println("Вывожу последние 5 самых успешных вариантов.\n" +
                        "Можно и больше, например: -show-last 10\n" +
                        "Для сохранения вариантов: -save-last 5\n")
                showLast.set(5)
            }
            preResults.forEach {it.profit = it.profit.toBigDecimal().setScale(2, RoundingMode.HALF_UP).toDouble()}
            preResults.sortBy {it.profit}
            //preResults.forEachIndexed { index, it ->  if (index >= 1 && it.profit == preResults.get(index - 1)) preResults.re}
            preResults = preResults.distinctBy { it.profit }.toMutableList()
            var tail = Math.min(showLast.get(0), preResults.size)
            preResults.takeLast(tail).forEach {
                //расшифровываем параметры
                decodeParams(it.params)
                println("${it.profit.toString()} %")

                //те параметры, которые указаны, выводим
                var paramString = ""
                paramArray.forEach {
                    if(it.getCurrent() is Double) paramString += " ${it.name} = ${it.getCurrent()}%\n"
                    else if(it.getCurrent() is Int) paramString += " ${it.name} = ${it.getCurrent()}\n"
                }
                println(paramString)
            }

            //сохраняем выбранные варианты в папку
            tail = Math.min(saveLast.get(0), preResults.size)
            if(saveLast.get(0) > 0) {
                println("Сохраняю последние ${saveLast.get(0)} самых удачных вариантов")
                trade.isSaveDeals = true

                //папка не существует? создаем
                val directoryPath = "optimized"
                val file = File(directoryPath)
                try {
                    if (file.exists()) file.delete()
                    else kotlin.io.path.Path(directoryPath).createDirectory()
                }
                catch(e: IOException) {
                    println("Ошибка: не могу создать папку для сохр результатов расчета:\n" +
                            file.absolutePath
                    )
                    return
                }

                preResults.takeLast(tail).forEachIndexed { i, it ->
                    //расшифровываем параметры
                    decodeParams(it.params)

                    trade.processData()
                    val dotIndex = outputName.lastIndexOf('.')
                    val outNameBase = if(dotIndex == -1) outputName else outputName.substring(0, dotIndex)
                    val outNameExt = if(dotIndex == -1) "" else outputName.substring(dotIndex)
                    val strFileNumber = i.toString().padStart(2, '0')
                    var nextOutName = "optimized${SEPARATOR}" + outNameBase + "-" + strFileNumber + outNameExt

                    if(saveFile(nextOutName, trade.dealsOutput)) println("${nextOutName} сохранен")
                    else return

                    val nextMonthName = "optimized${SEPARATOR}month-${strFileNumber}.csv"
                    if(saveMonths(nextMonthName, trade.profitDealsCnt, trade.lossDealsCnt, trade.profit,
                            trade.monthResults)) println("${nextMonthName} сохранен")
                    else return
                }
            }
        }
    }
}

fun decodeParams(arr: IntArray) : Boolean {
    paramArray.forEach { it.isEncoded = false }
    arr.forEach {
        for(i in 0..paramArray.size - 1) {
            if(it >= paramArray[i].start && it <= paramArray[i].end) {
                //этот параметр уже сохранен?
                if(paramArray[i].isEncoded) return false

                //ищем смещение от нач.значения параметра
                val pos = it - paramArray[i].start
                //тек значение = мин значение + шаг * смещение
                if(paramArray[i].get(0) is Double) {
                    val current = paramArray[i].get(0) as Double + paramArray[i].get(2) as Double * pos
                    paramArray[i].setCurrent(current)
                    paramArray[i].isEncoded = true
                }
                else if(paramArray[i].get(0) is Int) {
                    val current = paramArray[i].get(0) as Int + paramArray[i].get(2) as Int * pos
                    paramArray[i].setCurrent(current)
                    paramArray[i].isEncoded = true
                }
                break
            }
        }
    }
    return true
}

class IncorrectInputException(message: String) : Exception(message)

data class PreResult(var profit: Double, val params: IntArray)

//общий класс для параметров двух типов
interface Param {
    var start: Int
    var end: Int
    var name: String
    var isMulti: Boolean
    var isEncoded: Boolean
    fun getCurrent(): Any
    fun setCurrent(newCurrent: Any)
    fun read(argIterator: ListIterator<String>)
    fun get(index: Int): Any
}

//здесь храним параметр - значение, мин/макс, и диапазон для перебора
class ParamDouble() : Param {
    private var value = DoubleArray(3)
    override var start = 0
    override var end = 0
    override var name = ""
    override var isMulti = false
    override var isEncoded = false
    private var cur = 0.0

    //читаем от 1 до 3 значений и сохраняем их
    override fun read(argIterator: ListIterator<String>) {
        name = argIterator.previous()
        argIterator.next()
        var numParamSaved = 0
        for(i in 0..value.size - 1) {
            if(argIterator.hasNext()) {
                val dbl = argIterator.next().toDoubleOrNull()
                if (dbl != null) {
                    numParamSaved++
                    value[i] = dbl.toDouble()
                }
                else break
            }
        }
        isMulti = if(numParamSaved == 3) true else false
        if(!isMulti) argIterator.previous()

        //значение 1 не указано
        if(numParamSaved == 0) throw InvalidParameterException("")
        else cur = value[0]
    }

    override fun get(index: Int): Double {
        if(index < value.size) return value[index]
        else throw InvalidParameterException("Ошибка: параметр ${name}: выход за пределы массива\n")
    }

    override fun getCurrent(): Double {
        return cur
    }

    override fun setCurrent(newCurrent: Any) {
        if(newCurrent is Double) cur = newCurrent
    }
}

//здесь храним параметр - значение, мин/макс, и диапазон для перебора
class ParamInt() : Param {
    private var value = IntArray(3)
    override var start = 0
    override var end = 0
    override var name = ""
    override var isMulti = false
    override var isEncoded = false
    private var cur = 0

    //читаем от 1 до 3 значений и сохраняем их
    override fun read(argIterator: ListIterator<String>) {
        name = argIterator.previous()
        argIterator.next()
        var numParamSaved = 0
        for(i in 0..value.size - 1) {
            if(argIterator.hasNext()) {
                val dbl = argIterator.next().toIntOrNull()
                if (dbl != null) {
                    numParamSaved++
                    value[i] = dbl.toInt()
                }
                else break
            }
        }
        isMulti = if(numParamSaved == 3) true else false
        if(!isMulti) argIterator.previous()

        //значение 1 не указано
        if(numParamSaved == 0) throw InvalidParameterException("")
        else cur = value[0]
    }

    fun set(newInt :Int) {
        value[0] = newInt
    }

    override fun get(index: Int): Int {
        if(index < value.size) return value[index]
        else throw InvalidParameterException("Ошибка: параметр ${name}: выход за пределы массива\n")
    }

    override fun getCurrent(): Int {
        return cur
    }

    override fun setCurrent(newCurrent: Any) {
        if(newCurrent is Int) cur = newCurrent
    }
}

data class Deal (val dateTime: ZonedDateTime, var entryPrice: Double, val isLong: Boolean, var result: Double, var comment: String)

data class Candle (val dateTime: ZonedDateTime, val open: Double, val high: Double, val low: Double, val close: Double)

data class MonthResult(val name :String, val profit: String)

// класс для расчета стратегии с выбранными параметрами,
// общей прибыли и результатов по месяцам
class Trade(val candleList: List<Candle>, val dealsInput: List<Deal>) {
    var isLong = false
    var isSaveDeals = false
    var profit = 0.0
    var dealResult = 0.0
    var profitDealsCnt = 0
    var lossDealsCnt = 0
    var dealsOutput = mutableListOf<Deal>()
    var monthResults = mutableListOf<MonthResult>()
    private var isSkip = false
    var isPartProfit = false
    var deposit = depo
    var dealSumClosed = 0.0

    //рассчитываем результаты торговли
    fun processData() {
        var stop: Double = if (paramArray.any{it.name == "stop"}) paramArray.first{it.name == "stop"}.getCurrent() as Double else -1.0
        var trailingStop: Double = if (paramArray.any{it.name == "trailing-stop"}) paramArray.first{it.name == "trailing-stop"}.getCurrent() as Double else -1.0
        var entryFilter: Double = if (paramArray.any{it.name == "entry-filter"}) paramArray.first{it.name == "entry-filter"}.getCurrent() as Double else -1.0
        var entryWait: Int = if (paramArray.any{it.name == "entry-wait"}) paramArray.first{it.name == "entry-wait"}.getCurrent() as Int else -1
        var safeStop: Double = if (paramArray.any{it.name == "safe-stop"}) paramArray.first{it.name == "safe-stop"}.getCurrent() as Double else -1.0
        var takeProfit: Double = if (paramArray.any{it.name == "take-profit"}) paramArray.first{it.name == "take-profit"}.getCurrent() as Double else -1.0
        var partProfit: Double = if (paramArray.any{it.name == "part-profit"}) paramArray.first{it.name == "part-profit"}.getCurrent() as Double else -1.0
        var partFix: Double = if (paramArray.any{it.name == "part-fix"}) paramArray.first{it.name == "part-fix"}.getCurrent() as Double else -1.0
        isPartProfit = partProfit > -1.0 && partFix > -1.0
        var candleIt = candleList.listIterator()
        profit = 0.0
        dealResult = 0.0
        profitDealsCnt = 0
        lossDealsCnt = 0
        dealsOutput.clear()
        monthResults.clear()

        //обраб список сделок
        var nextEntryPrice = 0.0
        var nextDealDateTime: ZonedDateTime
        var barsInDeal = 0
        var candle = candleList[0]
        val maxDealIndex = dealsInput.size - 1
        val isSafeStop = safeStop > -1.0
        val isTrailStop = trailingStop > -1.0 || isSafeStop
        if (isSafeStop) trailingStop = safeStop
        val isTakeProfit = takeProfit > -1.0

        //результаты по месяцам
        var profitPerMonth = 0.0
        val month = arrayOf(
            "Январь",
            "Февраль",
            "Март",
            "Апрель",
            "Май",
            "Июнь",
            "Июль",
            "Август",
            "Сентябрь",
            "Октябрь",
            "Ноябрь",
            "Декабрь"
        )

        dealsInput.forEachIndexed { i, it ->
            //открываем шорт или лонг:
            barsInDeal = 0
            isLong = it.isLong
            dealResult = 0.0
            dealSumClosed = 0.0
            //dealSumOpened = 0.0
            var isEntryFilt = entryFilter > -1.0 && entryWait > -1
            isSkip = isEntryFilt
            var trailStopBase = it.entryPrice
            var trailStopCnt = 0
            var trailStopCurrent = 0.0
            var partTpStep = 1
            deposit = depo

            //пока не появилась следующая сделка, обраб свечи
            nextEntryPrice = if (i + 1 <= maxDealIndex) dealsInput.get(i + 1).entryPrice else it.entryPrice
            nextDealDateTime =
                if (i + 1 <= maxDealIndex) dealsInput.get(i + 1).dateTime else candleList[candleList.size - 1].dateTime

            while (candleIt.hasNext()) {
                candle = candleIt.next()
                barsInDeal++

                //свеча < даты открытия сделки, пропускаем
                if (candle.dateTime < it.dateTime) {
                    barsInDeal = 0
                    continue
                }
                //свеча = дате откр след сделки, выходим из цикла
                else if (candle.dateTime == nextDealDateTime) {
                    candleIt.previous() //перемещаем указатель назад на 1 свечу
                    break
                }

                //проверяем фильтр сделок: ждем движ цены в n% и m свечей от точки входа
                if (isEntryFilt && barsInDeal <= entryWait + 1) {
                    if (isLong && candle.high >= it.entryPrice + it.entryPrice * entryFilter / 100) {
                        it.entryPrice = it.entryPrice + it.entryPrice * entryFilter / 100
                        barsInDeal = 1
                        isSkip = false
                        isEntryFilt = false
                    } else if (!isLong && candle.low <= it.entryPrice - it.entryPrice * entryFilter / 100) {
                        it.entryPrice = it.entryPrice - it.entryPrice * entryFilter / 100
                        barsInDeal = 1
                        isSkip = false
                        isEntryFilt = false
                    }
                }

                //проверяем стопы
                if (!isSkip && stop > 0.0 && barsInDeal > 1 && dealResult == 0.0 && isLong) {
                    //var stopPrice = it.entryPrice - it.entryPrice * stop / 100
                    var stopPrice = calcStop(it.entryPrice, stop)
                    if (candle.low < stopPrice) {
                        isSkip = true
                        it.comment = "Stop"
                        calcProfit(it, stopPrice, commissionMarket.get(0) + commissionLimit.get(0))
                    }
                } else if (!isSkip && stop > 0.0 && barsInDeal > 1 && dealResult == 0.0 && !isLong) {
                    //var stopPrice = it.entryPrice + it.entryPrice * stop / 100
                    var stopPrice = calcStop(it.entryPrice, stop)
                    if (candle.high > stopPrice) {
                        isSkip = true
                        it.comment = "Stop"
                        calcProfit(it, stopPrice, commissionMarket.get(0) + commissionLimit.get(0))
                    }
                }

                //проверяем take-profit
                if (!isSkip && isTakeProfit && barsInDeal > 1) {
                    if (isLong && candle.high > calcStop(it.entryPrice, -takeProfit)) {
                        it.comment = "Take Profit"
                        isSkip = true
                        calcProfit(
                            it,
                            calcStop(it.entryPrice, -takeProfit),
                            commissionMarket.get(0) * commissionLimit.get(0)
                        )
                    } else if (!isLong && candle.low < calcStop(it.entryPrice, -takeProfit)) {
                        it.comment = "Take Profit"
                        isSkip = true
                        calcProfit(
                            it,
                            calcStop(it.entryPrice, -takeProfit),
                            commissionMarket.get(0) * commissionLimit.get(0)
                        )
                    }
                }

                //проверяем part-profit
                if(!isSkip && isPartProfit && barsInDeal > 1) {
                    if(isLong) {
                        val partTp = it.entryPrice + it.entryPrice * partProfit / 100 * partTpStep
                        if(candle.high >= partTp) { //цена прошла очередные +2%?
                            //вычисляем остаток
                            val priceStep = depo * partFix / 100
                            val sum = priceStep * partTpStep
                            if(sum <= depo + priceStep) {
                                val old = deposit
                                if(sum >= depo) isSkip = true
                                else deposit = priceStep
                                it.comment="Part Profit"
                                var comm = if(partTpStep > 1) commissionLimit.get(0) else commissionMarket.get(0) + commissionLimit.get(0)
                                calcProfit(it, partTp, comm)
                                val newDepo = old - depo * partFix / 100
                                deposit = if(newDepo > 0) newDepo else 0.0
                                partTpStep++
                            }
                        }
                    }
                    else {
                        val partTp = it.entryPrice - it.entryPrice * partProfit / 100 * partTpStep
                        if(candle.low <= partTp) { //цена прошла очередные +2%?
                            //вычисляем остаток
                            val priceStep = depo * partFix / 100
                            val sum = priceStep * partTpStep
                            if(sum <= depo + priceStep) {
                                val old = deposit
                                if(sum >= depo) isSkip = true
                                else deposit = priceStep
                                it.comment="Part Profit"
                                var comm = if(partTpStep > 1) commissionLimit.get(0) else commissionMarket.get(0) + commissionLimit.get(0)
                                calcProfit(it, partTp, comm)
                                val newDepo = old - depo * partFix / 100
                                deposit = if(newDepo > 0) newDepo else 0.0
                                partTpStep++
                            }
                        }
                    }
                }

                //проверяем trailing-stop
                if (!isSkip && isTrailStop && barsInDeal > 1) {
                    if (isLong) {
                        if (trailStopCnt > 0 && candle.low < trailStopCurrent) { //если стоп установлен и цена упала, фиксируем прибыль
                            isSkip = true
                            it.comment = if (isSafeStop) "Safe Stop" else "Trailing Stop"
                            calcProfit(it, trailStopCurrent, commissionMarket.get(0) + commissionLimit.get(0))
                        } else if (candle.high >= calcStop(
                                it.entryPrice,
                                -trailingStop - trailingStop * trailStopCnt
                            )
                        ) { //цена выросла на 3% от прошлого стопа, передвигаем стоп
                            if (isSafeStop) trailStopCnt = 0
                            trailStopBase = calcStop(
                                it.entryPrice,
                                -trailingStop * trailStopCnt
                            ) //очередная ступенька в 3%, от которой считаем
                            trailStopCurrent = calcStop(trailStopBase, -0.15) //перемещаем trailing-стоп
                            trailStopCnt = if (isSafeStop) 1; else trailStopCnt + 1
                        }
                    } else {
                        if (trailStopCnt > 0 && candle.high > trailStopCurrent) {
                            isSkip = true
                            it.comment = if (isSafeStop) "Safe Stop" else "Trailing Stop"
                            calcProfit(it, trailStopCurrent, commissionMarket.get(0) * commissionLimit.get(0))
                        } else if (candle.low <= calcStop(
                                it.entryPrice,
                                -trailingStop - trailingStop * trailStopCnt
                            )
                        ) { //цена выросла на 3% от прошлого стопа, передвигаем стоп
                            if (isSafeStop) trailStopCnt = 0
                            trailStopBase = calcStop(
                                it.entryPrice,
                                -trailingStop * trailStopCnt
                            ) //очередная ступенька в 3%, от которой считаем
                            trailStopCurrent = calcStop(trailStopBase, -0.15) //перемещаем trailing-стоп
                            trailStopCnt = if (isSafeStop) 1; else trailStopCnt + 1
                        }
                    }
                }
            }

            //и еще раз проверяем стопы
            if (!isSkip && stop > 0.0 && barsInDeal > 1 && dealResult == 0.0 && isLong) {
                //var stopPrice = it.entryPrice - it.entryPrice * stop / 100
                var stopPrice = calcStop(it.entryPrice, stop)
                if (candle.low < stopPrice) {
                    isSkip = true
                    it.comment = "Stop"
                    calcProfit(it, stopPrice, commissionMarket.get(0) + commissionLimit.get(0))
                }
            } else if (!isSkip && stop > 0.0 && barsInDeal > 1 && dealResult == 0.0 && !isLong) {
                //var stopPrice = it.entryPrice + it.entryPrice * stop / 100
                var stopPrice = calcStop(it.entryPrice, stop)
                if (candle.high > stopPrice) {
                    isSkip = true
                    it.comment = "Stop"
                    calcProfit(it, stopPrice, commissionMarket.get(0) + commissionLimit.get(0))
                }
            }

            //если фильтр не сработал
            if (isEntryFilt) it.comment = "Filter"

            //сделка не закрылась раньше чем нужно, считаем прибыль
            if (!isSkip && dealResult == 0.0) {
                isSkip = true
                calcProfit(it, nextEntryPrice, commissionMarket.get(0) * 2)
            }

            //считаем прибыль/убыток в %
            profit += dealResult
            profitPerMonth += dealResult

            if (dealResult > 0.0) profitDealsCnt++
            else if (dealResult < 0.0) lossDealsCnt++

            //подсчет результатов по месяцам
            if (i + 1 < maxDealIndex && dealsInput[i + 1].dateTime.monthValue != it.dateTime.monthValue) {
                monthResults.add(MonthResult(month[it.dateTime.monthValue - 1], "${profitPerMonth}"))
                profitPerMonth = 0.0
            }
            //сохраним последний месяц
            else if (i + 1 > maxDealIndex) {
                val prevDealMonth_ =
                    if (dealsInput.size >= 2) dealsInput[i - 1].dateTime.monthValue else it.dateTime.monthValue
                monthResults.add(MonthResult(month[prevDealMonth_ - 1], "${profitPerMonth}"))
                profitPerMonth = 0.0
            }
        }
    }

    //считаем прибыль за сделку в %
    fun calcProfit(deal: Deal, nextEntryPrice: Double, commission: Double) {
        val contracts = deposit / deal.entryPrice
        var sum = contracts * nextEntryPrice

        //сложный расчет?
        if(isPartProfit) {
            //пора закрываться?
            if(isSkip)
                closePartDeals(deal, nextEntryPrice, commission)

            //еще рано. добавляем в промежуточную сумму
            else
                dealSumClosed += (calcPreProfit(deal, nextEntryPrice, commission) / 100 + 1) * deposit
        }

        //все просто. одна сделка на вход = одна на выход
        else {
            dealResult = calcPreProfit(deal, nextEntryPrice, commission)
            if (isSaveDeals) dealsOutput.add(deal.copy(result = dealResult))
        }
    }

    //считаем прибыль за сделку в %, но ничего не сохраняем
    fun calcPreProfit(deal: Deal, nextEntryPrice: Double, commission: Double) : Double {
        if(deposit == 0.0) return 0.0
        val contracts = deposit / deal.entryPrice
        var sum = contracts * nextEntryPrice
        if (isLong) {
            sum = sum - sum * commission / 100
            return ((sum - deposit) / deposit * 100)
        } else {
            sum = sum + sum * commission / 100
            return ((deposit - sum) / deposit * 100)
        }
    }

    //part-profit - закрываем все частичные сделки
    fun closePartDeals(deal: Deal, nextEntryPrice: Double, commission: Double) {
        dealSumClosed += (calcPreProfit(deal, nextEntryPrice, commission) / 100 + 1) * deposit

        var delta = if(isLong) dealSumClosed - depo else - (depo - dealSumClosed)
        dealResult = delta / depo * 100
        dealSumClosed = 0.0
        isSkip = true
        if (isSaveDeals)
            dealsOutput.add(deal.copy(result = dealResult))
    }

    //считаем уровень стопа
    fun calcStop(startPrice: Double, percent: Double): Double {
        val contracts = depo / startPrice
        if (isLong)
            return (depo - depo * percent / 100) / contracts
        else
            return (depo + depo * percent / 100) / contracts
    }

}

//записываем выходной файл
fun saveFile(outputName: String, dealList: MutableList<Deal>) : Boolean {
    try {
        var out = File(outputName).bufferedWriter()
        var dateFormat = DateTimeFormatter.ofPattern("dd.MM")
        var timeFormat = DateTimeFormatter.ofPattern("HH:mm")

        dealList.forEach {
            val result = it.result.toBigDecimal().setScale(2, RoundingMode.HALF_UP).toDouble()
            //выводим сделку в таблицу результатов
            var title = if(it.isLong) "Long" else "Short"
            out.write("${it.dateTime.format(dateFormat)};" +
                "${it.dateTime.format(timeFormat)};" +
                    "${title};" +
                    "${result};" +
                    "${it.comment}\n")
        }
        out.close()
    }
    catch(e: IOException) {
        println("Ошибка записи файла $outputName")
        return false
    }

    return true
}

//сохраняем результаты по месяцам в отдельный файл
fun saveMonths(outName: String, profitDealsCnt: Int, lossDealsCnt: Int, profit: Double, monthResults: List<MonthResult>) : Boolean {
    try {
        var out = File(outName).bufferedWriter()

        var allProfitPercent = profit.toBigDecimal().setScale(2, RoundingMode.HALF_UP).toDouble()
        var depositFinish = (profit / 100 * depo).toInt()
        out.write("Оборот;Суммарное движение цены;Чистая прибыль за все время;Чистая прибыль %\n" +
                "\$${depo};${allProfitPercent}%;\$${depositFinish};${allProfitPercent}%\n\n" +
                "Прибыльные сделки;Убыточные сделки;;\n" +
                "${profitDealsCnt};${lossDealsCnt};;\n\n" +
                "Месяц;Процент прибыли;;\n")
        monthResults.forEach {
            val profit = it.profit.toBigDecimal().setScale(2, RoundingMode.HALF_UP).toDouble()
            out.write("${it.name};${profit}%;;\n")
        }

        var paramString = "\nПараметры стратегии:;;;\n" +
                "Имя;Значение;;\n"
        paramArray.forEach {
            if(it.getCurrent() is Double) paramString += "${it.name};${it.getCurrent()}%;;\n"
            else if(it.getCurrent() is Int) paramString += "${it.name};${it.getCurrent()};;\n"
        }
        out.write(paramString)

        out.close()
    }
    catch(e: IOException) {
        println("Ошибка записи файла ${outName}")
        return false
    }
    return true
}

//Расчет сочетаний
//Взято с
//http://zonakoda.ru/kak-najti-chislo-sochetanij-iz-n-ehlementov-po-m-2.html
class Combinations(val n: Int, var m: Int) {
    private var arr = IntArray(m)
    var numVariants = 0
    private var cnt = 0
    init {
        if(n == 0 || m == 0 || m > n) throw InvalidParameterException("Расчет сочетаний - Ошибка: m > n или m = 0 или n = 0\n")

        for (i in 0..m - 1) {
            arr[i] = i + 1
        }

        var akk = n - m + 1
        var k = akk + 1
        for(i in 2..m) {
            akk = akk / i * k + akk % i * k / i
            k++
        }
        numVariants = akk
    }

    fun hasNext(): Boolean {
        return cnt < numVariants
    }

    fun next() :IntArray {
        //первый вызов?
        cnt++
        if(cnt == 1) return arr

        //нет, считаем
        for(i in m - 1 downTo 0) {
            if (arr[i] < n - m + i + 1)
            {
                arr[i]++
                for(j in i..m - 2) {
                    arr[j + 1] = arr[j] + 1
                }
                return arr
            }
        }
        return IntArray(n)
    }
}
