package ppl.dsl.optiml.datastruct.scala

class VectorViewImpl[@specialized T: ClassManifest](x: Array[T], offset: Int, str: Int, len: Int, row_vec: Boolean) extends VectorView[T]{

  protected var _data: Array[T] = x
  protected var _length = len
  protected var _isRow = row_vec
  protected var _start = offset
  protected var _stride = str

  def start = _start
  def stride = _stride
  def length = _length
  def isRow = _isRow

  def idx(n: Int) = _start + n*_stride

  def apply(n: Int) : T = {
    _data(idx(n))
  }

  def update(n: Int, x: T) {
    _data(idx(n)) = x
  }

  def insert(pos:Int, x: T): VectorViewImpl[T] = {
    throw new UnsupportedOperationException("operations on views not supported yet")
  }

  protected def chkIndex(index: Int) = {
    if (index < 0 || index >= _data.length)
      throw new IndexOutOfBoundsException
    index
  }
}