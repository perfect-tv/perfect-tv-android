package top.rootu.perfecttv.ui

import top.rootu.perfecttv.data.Programme

/**
 * Ячейка ленты канала: передача из EPG или получасовой слот в промежутке без программы
 * (так по каналу без EPG можно ходить по сетке и смотреть архив с шагом 30 минут).
 * Ячейки сетки гида: передачи и пустые получасовые слоты в окне времени.
 */
class Cell(val start: Long, val end: Long, val program: Programme?) {
    val title get() = program?.title ?: ""

    /** Заголовок для карточки и архива: у пустого слота — время */
    fun label() = program?.title ?: Fmt.timeRange(start, end)
}

object Cells {
    const val SLOT = 1800L

    private fun floorSlot(t: Long) = Math.floorDiv(t, SLOT) * SLOT

    /** Непрерывная лента на [from, to) */
    fun cells(programs: List<Programme>, from: Long, to: Long): List<Cell> {
        val out = ArrayList<Cell>()
        var t = from
        fun gap(end: Long) {
            while (t < end) {
                val slotEnd = minOf(end, floorSlot(t) + SLOT)
                out.add(Cell(t, slotEnd, null))
                t = slotEnd
            }
        }
        for (p in programs) {
            if (t >= to) break
            if (p.stop <= t) continue
            if (p.start > t) gap(minOf(p.start, to))
            if (t >= to) break
            out.add(Cell(p.start, p.stop, p))
            t = p.stop
        }
        gap(to)
        return out
    }

    /** Ячейка, содержащая момент t */
    fun at(programs: List<Programme>, t: Long): Cell {
        var prevEnd = Long.MIN_VALUE
        for (p in programs) {
            if (p.start <= t && t < p.stop) return Cell(p.start, p.stop, p)
            if (p.start > t) {
                val start = maxOf(floorSlot(t), prevEnd)
                return Cell(start, minOf(p.start, floorSlot(t) + SLOT), null)
            }
            prevEnd = p.stop
        }
        return Cell(maxOf(floorSlot(t), prevEnd), floorSlot(t) + SLOT, null)
    }
}
