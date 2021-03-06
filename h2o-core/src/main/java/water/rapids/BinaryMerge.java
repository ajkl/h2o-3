package water.rapids;

// Since we have a single key field in H2O (different to data.table), bmerge() becomes a lot simpler (no
// need for recursion through join columns) with a downside of transfer-cost should we not need all the key.

import water.*;
import water.fvec.Chunk;
import water.fvec.Frame;
import water.fvec.NewChunk;
import water.fvec.Vec;
import static water.rapids.SingleThreadRadixOrder.getSortedOXHeaderKey;
import water.util.ArrayUtils;
import water.util.Log;

import java.util.Arrays;

public class BinaryMerge extends DTask<BinaryMerge> {
  long _numRowsInResult=0;  // returned to caller, so not transient
  int _chunkSizes[];      // TODO:  only _chunkSizes.length is needed by caller, so return that length only
  double _timings[];

  transient long _retFirst[/*n2GB*/][];  // The row number of the first right table's index key that matches
  transient long _retLen[/*n2GB*/][];    // How many rows does it match to?
  transient byte _leftKey[/*n2GB*/][/*i mod 2GB * _keySize*/];
  transient byte _rightKey[][];
  transient long _leftOrder[/*n2GB*/][/*i mod 2GB * _keySize*/];
  transient long _rightOrder[][];
  transient boolean _oneToManyMatch = false;   // does any left row match to more than 1 right row?  If not, can allocate and loop more efficiently, and mark the resulting key'd frame with a 'unique' index.
                                               //      TODO: implement
  int _leftFieldSizes[], _rightFieldSizes[];  // the widths of each column in the key
  transient int _leftKeyNCol, _rightKeyNCol;  // the number of columns in the key i.e. length of _leftFieldSizes and _rightFieldSizes
  transient int _leftKeySize, _rightKeySize;   // the total width in bytes of the key, sum of field sizes
  transient int _numJoinCols;
  transient long _leftN, _rightN;
  transient long _leftBatchSize, _rightBatchSize;
  Frame _leftFrame, _rightFrame;

  transient long _perNodeNumRightRowsToFetch[];
  transient long _perNodeNumLeftRowsToFetch[];
  int _leftMSB, _rightMSB;

  boolean _allLeft, _allRight;

  Vec _leftVec, _rightVec;

  transient int _leftChunkNode[], _rightChunkNode[];  // fast lookups to save repeated calls to node.index() which calls binarysearch within it.

  BinaryMerge(Frame leftFrame, Frame rightFrame, int leftMSB, int rightMSB, int leftFieldSizes[], int rightFieldSizes[], boolean allLeft) {   // In X[Y], 'left'=i and 'right'=x
    _leftFrame = leftFrame;
    _rightFrame = rightFrame;
    _leftMSB = leftMSB;
    _rightMSB = rightMSB;
    _leftFieldSizes = leftFieldSizes;
    _rightFieldSizes = rightFieldSizes;
    _allLeft = allLeft;
    _allRight = false;  // TODO: pass through
    // TODO: set 2 Frame and 2 int[] to NULL at the end of compute2 to save some traffic back, but should be small and insignificant
  }

  @Override
  protected void compute2() {
    _timings = new double[20];
    long t0 = System.nanoTime();
    SingleThreadRadixOrder.OXHeader leftSortedOXHeader = DKV.getGet(getSortedOXHeaderKey(/*left=*/true, _leftMSB));
    if (leftSortedOXHeader == null) {
      if (_allRight) throw H2O.unimpl();  // TODO pass through _allRight and implement
      tryComplete(); return;
    }
    SingleThreadRadixOrder.OXHeader rightSortedOXHeader = DKV.getGet(getSortedOXHeaderKey(/*left=*/false, _rightMSB));
    if (rightSortedOXHeader == null) {
      if (_allLeft == false) { tryComplete(); return; }
      rightSortedOXHeader = new SingleThreadRadixOrder.OXHeader(0, 0, 0);  // enables general case code to run below without needing new special case code
    }
    // both left and right MSB have some data to match
    _leftBatchSize = leftSortedOXHeader._batchSize;
    _rightBatchSize = rightSortedOXHeader._batchSize;
    _perNodeNumRightRowsToFetch = new long[H2O.CLOUD.size()];
    _perNodeNumLeftRowsToFetch = new long[H2O.CLOUD.size()];

    // get left batches
    _leftKey = new byte[leftSortedOXHeader._nBatch][];
    _leftOrder = new long[leftSortedOXHeader._nBatch][];
    _retFirst = new long[leftSortedOXHeader._nBatch][];
    _retLen = new long[leftSortedOXHeader._nBatch][];
    for (int b=0; b<leftSortedOXHeader._nBatch; ++b) {
      SplitByMSBLocal.OXbatch oxLeft = DKV.getGet(SplitByMSBLocal.getSortedOXbatchKey(/*left=*/true, _leftMSB, b));
      _leftKey[b] = oxLeft._x;
      _leftOrder[b] = oxLeft._o;
      _retFirst[b] = new long[oxLeft._o.length];
      _retLen[b] = new long[oxLeft._o.length];
    }
    _leftN = leftSortedOXHeader._numRows;

    // get right batches
    _rightKey = new byte[rightSortedOXHeader._nBatch][];
    _rightOrder = new long[rightSortedOXHeader._nBatch][];
    for (int b=0; b<rightSortedOXHeader._nBatch; ++b) {
      SplitByMSBLocal.OXbatch oxRight = DKV.getGet(SplitByMSBLocal.getSortedOXbatchKey(/*left=*/false, _rightMSB, b));
      _rightKey[b] = oxRight._x;
      _rightOrder[b] = oxRight._o;
    }
    _rightN = rightSortedOXHeader._numRows;

    _leftKeyNCol = _leftFieldSizes.length;
    _rightKeyNCol = _rightFieldSizes.length;
    _leftKeySize = ArrayUtils.sum(_leftFieldSizes);
    _rightKeySize = ArrayUtils.sum(_rightFieldSizes);
    System.out.println("_leftKeySize="+_leftKeySize + " _rightKeySize="+_rightKeySize + " _leftN="+_leftN + " _rightN="+_rightN);
    _numJoinCols = Math.min(_leftKeyNCol, _rightKeyNCol);

    // Create fast lookups to go from chunk index to node index of that chunk
    // TODO: must these be created for each and every instance?  Only needed once per node.
    _leftChunkNode = new int[_leftFrame.anyVec().nChunks()];
    _rightChunkNode = new int[_rightFrame.anyVec().nChunks()];
    for (int i=0; i<_leftFrame.anyVec().nChunks(); i++) {
      _leftChunkNode[i] = _leftFrame.anyVec().chunkKey(i).home_node().index();
    }
    for (int i=0; i<_rightFrame.anyVec().nChunks(); i++) {
      _rightChunkNode[i] = _rightFrame.anyVec().chunkKey(i).home_node().index();
    }

    _leftVec = _leftFrame.anyVec();
    _rightVec = _rightFrame.anyVec();

    _timings[0] = (System.nanoTime() - t0) / 1e9;

    if ((_leftN != 0 || _allRight) && (_rightN != 0 || _allLeft)) {

      t0 = System.nanoTime();
      bmerge_r(-1, _leftN, -1, _rightN);
      _timings[1] = (System.nanoTime() - t0) / 1e9;

      if (_numRowsInResult > 0) createChunksInDKV();
    }

    // TODO: recheck transients or null out here before returning
    tryComplete();
  }


  private int keycmp(byte x[][], long xi, byte y[][], long yi) {   // TO DO - faster way closer to CPU like batches of long compare, maybe.
    //long t0 = System.nanoTime();
    //_timings[12] += 1;
    byte xbatch[] = x[(int)(xi / _leftBatchSize)];
    byte ybatch[] = y[(int)(yi / _rightBatchSize)];
    int xoff = (int)(xi % _leftBatchSize) * _leftKeySize;
    int yoff = (int)(yi % _rightBatchSize) * _rightKeySize;
    byte xByte=0, yByte=0;
    // TODO: switch to use keycmp_sameShape() for common case of all(leftFieldSizes == rightFieldSizes), although, skipping to current column will
    //        help save repeating redundant work and saving the outer for() loop and one if() may not be worth it.
    int i=0, xlen=0, ylen=0;

    while (i<_numJoinCols && xlen==0) {    // TO DO: pass i in to start at a later key column, when known
      xlen = _leftFieldSizes[i];
      ylen = _rightFieldSizes[i];
      if (xlen!=ylen) {
        while (xlen>ylen && xbatch[xoff]==0) { xoff++; xlen--; }
        while (ylen>xlen && ybatch[yoff]==0) { yoff++; ylen--; }
        if (xlen!=ylen) {
          //_timings[13] += (System.nanoTime() - t0)/1e9;
          return (xlen - ylen);
        }
      }
      while (xlen>0 && (xByte=xbatch[xoff])==(yByte=ybatch[yoff])) { xoff++; yoff++; xlen--; }
      i++;
    }
    //_timings[13] += (System.nanoTime() - t0)/1e9;
    return (xByte & 0xFF) - (yByte & 0xFF);
    // Same return value as strcmp in C. <0 => xi<yi
  }

  private void bmerge_r(long lLowIn, long lUppIn, long rLowIn, long rUppIn) {
    // TO DO: parallel each of the 256 bins
    long lLow = lLowIn, lUpp = lUppIn, rLow = rLowIn, rUpp = rUppIn;
    long mid, tmpLow, tmpUpp;
    long lr = lLow + (lUpp - lLow) / 2;   // i.e. (lLow+lUpp)/2 but being robust to one day in the future someone somewhere overflowing long; e.g. 32 exabytes of 1-column ints
    while (rLow < rUpp - 1) {
      mid = rLow + (rUpp - rLow) / 2;
      int cmp = keycmp(_leftKey, lr, _rightKey, mid);  // -1, 0 or 1, like strcmp
      if (cmp < 0) {
        rUpp = mid;
      } else if (cmp > 0) {
        rLow = mid;
      } else { // rKey == lKey including NA == NA
        // branch mid to find start and end of this group in this column
        // TO DO?: not if mult=first|last and col<ncol-1
        tmpLow = mid;
        tmpUpp = mid;
        while (tmpLow < rUpp - 1) {
          mid = tmpLow + (rUpp - tmpLow) / 2;
          if (keycmp(_leftKey, lr, _rightKey, mid) == 0) tmpLow = mid;
          else rUpp = mid;
        }
        while (rLow < tmpUpp - 1) {
          mid = rLow + (tmpUpp - rLow) / 2;
          if (keycmp(_leftKey, lr, _rightKey, mid) == 0) tmpUpp = mid;
          else rLow = mid;
        }
        break;
      }
    }
    // rLow and rUpp now surround the group in the right table.

    // The left table key may (unusually, and not recommended, but sometimes needed) be duplicated.
    // Linear search outwards from left row. Most commonly, the first test shows this left key is unique.
    // This saves i) re-finding the matching rows in the right for all the dup'd left and ii) recursive bounds logic gets awkward if other left rows can find the same right rows
    // Related to 'allow.cartesian' in data.table.
    // TO DO:  if index stores attribute that it is unique then we don't need this step. However, each of these while()s would run at most once in that case, which may not be worth optimizing.
    tmpLow = lr + 1;
    while (tmpLow<lUpp && keycmp(_leftKey, tmpLow, _leftKey, lr)==0) tmpLow++;
    lUpp = tmpLow;
    tmpUpp = lr - 1;
    while (tmpUpp>lLow && keycmp(_leftKey, tmpUpp, _leftKey, lr)==0) tmpUpp--;
    lLow = tmpUpp;
    // lLow and lUpp now surround the group in the left table.  If left key is unique then lLow==lr-1 and lUpp==lr+1.

    long len = rUpp - rLow - 1;  // if value found, rLow and rUpp surround it, unlike standard binary search where rLow falls on it
    // TO DO - we don't need loop here :)  Why does perNodeNumRightRowsToFetch increase so much?
    if (len > 0 || _allLeft) {
      long t0 = System.nanoTime();
      if (len > 1) _oneToManyMatch = true;
      _numRowsInResult += Math.max(1,len) * (lUpp-lLow-1);   // 1 for NA row when _allLeft
      for (long j = lLow + 1; j < lUpp; j++) {   // usually iterates once only for j=lr, but more than once if there are dup keys in left table
        {
          // may be a range of left dup'd join-col values, but we need to fetch each one since the left non-join columns are likely not dup'd and may be the reason for the cartesian join
          long t00 = System.nanoTime();
          long globalRowNumber = _leftOrder[(int)(j / _leftBatchSize)][(int)(j % _leftBatchSize)];
          _timings[17] += (System.nanoTime() - t00)/1e9;
          t00 = System.nanoTime();
          int chkIdx = _leftVec.elem2ChunkIdx(globalRowNumber); //binary search in espc
          _timings[15] += (System.nanoTime() - t00)/1e9;
          _perNodeNumLeftRowsToFetch[_leftChunkNode[chkIdx]]++;  // the key is the same within this left dup range, but still need to fetch left non-join columns
        }
        if (len==0) continue;  // _allLeft must be true if len==0
        int jb = (int)(j/_leftBatchSize);
        int jo = (int)(j%_leftBatchSize);
        _retFirst[jb][jo] = rLow + 2;  // rLow surrounds row, so +1.  Then another +1 for 1-based row-number. 0 (default) means nomatch and saves extra set to -1 for no match.  Could be significant in large edge cases by not needing to write at all to _retFirst if it has no matches.
        _retLen[jb][jo] = len;
        //StringBuilder sb = new StringBuilder();
        //sb.append("Left row " + _leftOrder[jb][jo] + " matches to " + _retLen[jb][jo] + " right rows: ");
        long a = _retFirst[jb][jo] -1;
        long iLim = _retLen[jb][jo];
        for (long i=0; i<iLim; i++) {
          long loc = a+i;
          //sb.append(_rightOrder[(int)(loc / _rightBatchSize)][(int)(loc % _rightBatchSize)] + " ");
          long t00 = System.nanoTime();
          long globalRowNumber = _rightOrder[(int)(loc / _rightBatchSize)][(int)(loc % _rightBatchSize)];
          _timings[18] += (System.nanoTime() - t00)/1e9;
          t00 = System.nanoTime();
          int chkIdx = _rightVec.elem2ChunkIdx(globalRowNumber); //binary search in espc
          _timings[16] += (System.nanoTime() - t00)/1e9;
          _perNodeNumRightRowsToFetch[_rightChunkNode[chkIdx]]++;  // just count the number per node. So we can allocate arrays precisely up front, and also to return early to use in case of memory errors or other distribution problems
        }
        //Log.info(sb);
      }
      _timings[14] += (System.nanoTime() - t0)/1e9;
    }
    // TO DO: check assumption that retFirst and retLength are initialized to 0, for case of no match
    // Now branch (and TO DO in parallel) to merge below and merge above
    if (lLow > lLowIn && rLow > rLowIn)
      bmerge_r(lLowIn, lLow + 1, rLowIn, rLow+1);
    if (lUpp < lUppIn && rUpp < rUppIn)
      bmerge_r(lUpp-1, lUppIn, rUpp-1, rUppIn);

    // We don't feel tempted to reduce the global _ansN here and make a global frame,
    // since we want to process each MSB l/r combo individually without allocating them all.
    // Since recursive, no more code should be here (it would run too much)
  }

  private void createChunksInDKV() {

    // Collect all matches
    // Create the final frame (part) for this MSB combination
    // Cannot use a List<Long> as that's restricted to 2Bn items and also isn't an Iced datatype
    long t0 = System.nanoTime();
    long perNodeRightRows[][][] = new long[H2O.CLOUD.size()][][];
    long perNodeRightRowsFrom[][][] = new long[H2O.CLOUD.size()][][];
    long perNodeRightLoc[] = new long[H2O.CLOUD.size()];

    long perNodeLeftRows[][][] = new long[H2O.CLOUD.size()][][];
    long perNodeLeftRowsFrom[][][] = new long[H2O.CLOUD.size()][][];
    long perNodeLeftRowsRepeat[][][] = new long[H2O.CLOUD.size()][][];
    long perNodeLeftLoc[] = new long[H2O.CLOUD.size()];

    // Allocate memory to split this MSB combn's left and right matching rows into contiguous batches sent to the nodes they reside on
    int batchSize = 256*1024*1024 / 8;  // 256GB DKV limit / sizeof(long)
    for (int i = 0; i < H2O.CLOUD.size(); i++) {
      if (_perNodeNumRightRowsToFetch[i] > 0) {
        int nbatch = (int) ((_perNodeNumRightRowsToFetch[i] - 1) / batchSize + 1);  // TODO: wrap in class to avoid this boiler plate
        int lastSize = (int) (_perNodeNumRightRowsToFetch[i] - (nbatch - 1) * batchSize);
        assert nbatch >= 1;
        assert lastSize > 0;
        perNodeRightRows[i] = new long[nbatch][];
        perNodeRightRowsFrom[i] = new long[nbatch][];
        int b;
        for (b = 0; b < nbatch - 1; b++) {
          perNodeRightRows[i][b] = new long[batchSize];  // TO DO?: use MemoryManager.malloc()
          perNodeRightRowsFrom[i][b] = new long[batchSize];
        }
        perNodeRightRows[i][b] = new long[lastSize];
        perNodeRightRowsFrom[i][b] = new long[lastSize];
      }
      if (_perNodeNumLeftRowsToFetch[i] > 0) {
        int nbatch = (int) ((_perNodeNumLeftRowsToFetch[i] - 1) / batchSize + 1);  // TODO: wrap in class to avoid this boiler plate
        int lastSize = (int) (_perNodeNumLeftRowsToFetch[i] - (nbatch - 1) * batchSize);
        assert nbatch >= 1;
        assert lastSize > 0;
        perNodeLeftRows[i] = new long[nbatch][];
        perNodeLeftRowsFrom[i] = new long[nbatch][];
        perNodeLeftRowsRepeat[i] = new long[nbatch][];
        int b;
        for (b = 0; b < nbatch - 1; b++) {
          perNodeLeftRows[i][b] = new long[batchSize];  // TO DO?: use MemoryManager.malloc()
          perNodeLeftRowsFrom[i][b] = new long[batchSize];
          perNodeLeftRowsRepeat[i][b] = new long[batchSize];
        }
        perNodeLeftRows[i][b] = new long[lastSize];
        perNodeLeftRowsFrom[i][b] = new long[lastSize];
        perNodeLeftRowsRepeat[i][b] = new long[lastSize];
      }
    }
    _timings[2] = (System.nanoTime() - t0) / 1e9;
    t0 = System.nanoTime();

    // Loop over _retFirst and _retLen and populate the batched requests for each node helper
    // _retFirst and _retLen are the same shape
    long resultLoc=0;  // sweep upwards through the final result, filling it in
    long leftLoc=-1;   // sweep through left table along the sorted row locations.  // TODO: hop back to original order here for [] syntax.
    for (int jb=0; jb<_retFirst.length; ++jb) {              // jb = j batch
      for (int jo=0; jo<_retFirst[jb].length; ++jo) {        // jo = j offset
        leftLoc++;  // to save jb*_retFirst[0].length + jo;
        long f = _retFirst[jb][jo];
        long l = _retLen[jb][jo];
        if (f==0) {
          // left row matches to no right row
          assert l == 0;  // doesn't have to be 0 (could be 1 already if allLeft==true) but currently it should be, so check it
          if (!_allLeft) continue;
          // now insert the left row once and NA for the right columns i.e. left outer join
        }
        { // new scope so 'row' can be declared in the for() loop below and registerized (otherwise 'already defined in this scope' in that scope)
          // Fetch the left rows and mark the contiguous from-ranges each left row should be recycled over
          long row = _leftOrder[(int)(leftLoc / _leftBatchSize)][(int)(leftLoc % _leftBatchSize)];
          Vec v = _leftFrame.anyVec();
          int chkIdx = v.elem2ChunkIdx(row); //binary search in espc
          int ni = _leftChunkNode[chkIdx];
          long pnl = perNodeLeftLoc[ni]++;   // pnl = per node location
          perNodeLeftRows[ni][(int)(pnl/batchSize)][(int)(pnl%batchSize)] = row;  // ask that node for global row number row
          perNodeLeftRowsFrom[ni][(int)(pnl/batchSize)][(int)(pnl%batchSize)] = resultLoc;  // TODO: could store the batch and offset separately?  If it will be used to assign into a Vec, then that's have different shape/espc so the location is better.
          perNodeLeftRowsRepeat[ni][(int)(pnl/batchSize)][(int)(pnl%batchSize)] = Math.max(1,l);
        }
        if (f==0) { resultLoc++; continue; }
        assert l > 0;
        for (int r=0; r<l; r++) {
          long loc = f+r-1;  // -1 because these are 0-based where 0 means no-match and 1 refers to the first row
          long row = _rightOrder[(int)(loc / _rightBatchSize)][(int)(loc % _rightBatchSize)];   // TODO: could take / and % outside loop in cases where it doesn't span a batch boundary
          // find the owning node for the row, using local operations here
          Vec v = _rightFrame.anyVec();
          int chkIdx = v.elem2ChunkIdx(row); //binary search in espc
          int ni = _rightChunkNode[chkIdx];
          long pnl = perNodeRightLoc[ni]++;   // pnl = per node location
          perNodeRightRows[ni][(int)(pnl/batchSize)][(int)(pnl%batchSize)] = row;  // ask that node for global row number row
          perNodeRightRowsFrom[ni][(int)(pnl/batchSize)][(int)(pnl%batchSize)] = resultLoc++;  // TODO: could store the batch and offset separately?  If it will be used to assign into a Vec, then that's have different shape/espc so the location is better.
        }
      }
    }
    _timings[3] = (System.nanoTime() - t0) / 1e9;
    t0 = System.nanoTime();

    // Create the chunks for the final frame from this MSB pair.
    batchSize = 256*1024*1024 / 16;  // number of rows per chunk to fit in 256GB DKV limit.   16 bytes for each UUID (biggest type). Enum will be long (8). TODO: How is non-Enum 'string' handled by H2O?
    int nbatch = (int) (_numRowsInResult-1)/batchSize +1;  // TODO: wrap in class to avoid this boiler plate
    int lastSize = (int)(_numRowsInResult - (nbatch-1)*batchSize);
    assert nbatch >= 1;
    assert lastSize > 0;
    _chunkSizes = new int[nbatch];
    int _numLeftCols = _leftFrame.numCols();
    int _numColsInResult = _leftFrame.numCols() + _rightFrame.numCols() - _numJoinCols;
    double[][][] frameLikeChunks = new double[_numColsInResult][nbatch][]; //TODO: compression via int types
    for (int col=0; col<_numColsInResult; col++) {
      int b;
      for (b = 0; b < nbatch - 1; b++) {
        frameLikeChunks[col][b] = new double[batchSize];
        Arrays.fill(frameLikeChunks[col][b], Double.NaN);   // NA by default to save filling with NA for nomatches when allLeft
        _chunkSizes[b] = batchSize;
      }
      frameLikeChunks[col][b] = new double[lastSize];
      Arrays.fill(frameLikeChunks[col][b], Double.NaN);
      _chunkSizes[b] = lastSize;
    }
    _timings[4] = (System.nanoTime() - t0) / 1e9;
    t0 = System.nanoTime();

    RPC<GetRawRemoteRows> grrrsRite[][] = new RPC[H2O.CLOUD.size()][];
    RPC<GetRawRemoteRows> grrrsLeft[][] = new RPC[H2O.CLOUD.size()][];
    for (H2ONode node : H2O.CLOUD._memary) {
      int ni = node.index();
      // TODO: Ask Cliff why node.index() calls binarysearch within it? He saw that coming up in profiling.
      int bUppRite = perNodeRightRows[ni] == null ? 0 : perNodeRightRows[ni].length;
      int bUppLeft =  perNodeLeftRows[ni] == null ? 0 :  perNodeLeftRows[ni].length;
      grrrsRite[ni] = new RPC[bUppRite];
      grrrsLeft[ni] = new RPC[bUppLeft];
      for (int b = 0; b < bUppRite; b++) {
        // Arrays.sort(perNodeRightRows[ni][b]);  Simple quick test of fetching in monotic order. Doesn't seem to help so far.
        grrrsRite[ni][b] = new RPC<>(node, new GetRawRemoteRows(_rightFrame, perNodeRightRows[ni][b])).call();
      }
      for (int b = 0; b < bUppLeft; b++) {
        // Arrays.sort(perNodeLeftRows[ni][b]);
        grrrsLeft[ni][b] = new RPC<>(node, new GetRawRemoteRows(_leftFrame, perNodeLeftRows[ni][b])).call();
      }
    }
    for (H2ONode node : H2O.CLOUD._memary) {  // TODO:  this shouldn't be serially waiting for the first result.  Go parallel as soon as arrive. Callback? Parallel queue?
      int ni = node.index();
      int bUppRite = perNodeRightRows[ni] == null ? 0 : perNodeRightRows[ni].length;
      int bUppLeft =  perNodeLeftRows[ni] == null ? 0 :  perNodeLeftRows[ni].length;
      for (int b = 0; b < bUppRite; b++) {
        t0 = System.nanoTime();
        GetRawRemoteRows grrr = grrrsRite[ni][b].get();
        _timings[5] += (System.nanoTime() - t0) / 1e9;
        _timings[6] += grrr.timeTaken;
        t0 = System.nanoTime();
        assert (grrr._rows == null);
        double[][]/*Chunk[]*/ chks = grrr._chk;
        for (int col = 0; col < _numColsInResult - _numLeftCols; col++) {   // TODO: currently join columns must be the first _numJoinCols. Relax.
          double chk[] = chks[_numJoinCols + col];
          for (int row = 0; row < chk.length /*.len()*/; row++) {
            long actualRowInMSBCombo = perNodeRightRowsFrom[ni][b][row];
            int whichChunk = (int) (actualRowInMSBCombo / batchSize);
            int offset = (int) (actualRowInMSBCombo % batchSize);
            frameLikeChunks[_numLeftCols + col][whichChunk][offset] = chk[row];  // colForBatch.atd(row); TODO: this only works for numeric columns (not for date, UUID, strings, etc.)
          }
        }
        _timings[7] += (System.nanoTime() - t0) / 1e9;
      }

      for (int b = 0; b < bUppLeft; b++) {
        t0 = System.nanoTime();
        GetRawRemoteRows grrr = grrrsLeft[ni][b].get();
        _timings[8] += (System.nanoTime() - t0) / 1e9;
        _timings[9] += grrr.timeTaken;
        t0 = System.nanoTime();
        assert (grrr._rows == null);
        double[][]/*Chunk[]*/ chks = grrr._chk;
        for (int col = 0; col < chks.length; ++col) {
          double chk[] = chks[col];
          for (int row = 0; row < chk.length; row++) {
            long actualRowInMSBCombo = perNodeLeftRowsFrom[ni][b][row];
            for (int rep = 0; rep < perNodeLeftRowsRepeat[ni][b][row]; rep++) {
              long a = actualRowInMSBCombo + rep;
              int whichChunk = (int) (a / batchSize);  // TO DO: loop into batches to save / and % for each repeat and still cater for crossing multiple batch boundaries
              int offset = (int) (a % batchSize);
              frameLikeChunks[col][whichChunk][offset] = chk[row];  // colForBatch.atd(row); TODO: this only works for numeric columns (not for date, UUID, strings, etc.)
            }
          }
        }
      }
      _timings[10] += (System.nanoTime() - t0) / 1e9;
    }
    t0 = System.nanoTime();

    // compress all chunks and store them
    Futures fs = new Futures();
    for (int col=0; col<_numColsInResult; col++) {
      for (int b = 0; b < nbatch; b++) {
        Chunk ck = new NewChunk(frameLikeChunks[col][b]).compress();
        DKV.put(getKeyForMSBComboPerCol(/*_leftFrame, _rightFrame,*/ _leftMSB, _rightMSB, col, b), ck, fs, true);
        frameLikeChunks[col][b]=null; //free mem as early as possible (it's now in the store)
      }
    }
    fs.blockForPending();
    _timings[11] = (System.nanoTime() - t0) / 1e9;

  }

  static Key getKeyForMSBComboPerCol(/*Frame leftFrame, Frame rightFrame,*/ int leftMSB, int rightMSB, int col /*final table*/, int batch) {
    return Key.make("__binary_merge__Chunk_for_col" + col + "_batch" + batch
                    // + rightFrame._key.toString() + "_joined_with" + leftFrame._key.toString()
                    + "_leftMSB" + leftMSB + "_rightMSB" + rightMSB,
            (byte) 1, Key.HIDDEN_USER_KEY, false, SplitByMSBLocal.ownerOfMSB(rightMSB)
    ); //TODO home locally
  }

  class GetRawRemoteRows extends DTask<GetRawRemoteRows> {
    double[/*col*/][] _chk; //null on the way to remote node, non-null on the way back
    long[/*rows*/] _rows; //which rows to fetch from remote node, non-null on the way to remote, null on the way back
    double timeTaken;
    Frame _fr;
    GetRawRemoteRows(Frame fr, long[] rows) {
      _rows = rows;
      _fr = fr;
      _priority = nextThrPriority();  // bump locally AND ship this priority to the worker where the priority() getter will query it
    }
    @Override public byte priority() { return _priority; }
    private byte _priority;
    // Raise the priority, so that if a thread blocks here, we are guaranteed
    // the task completes (perhaps using a higher-priority thread from the
    // upper thread pools).  This prevents thread deadlock.
    // Remember that this gets queried on both the caller and the sender, of course.

    @Override
    protected void compute2() {
      assert(_rows!=null);
      assert(_chk ==null);
      long t0 = System.nanoTime();

      _chk  = new double[_fr.numCols()][_rows.length];
      int cidx[] = new int[_rows.length];
      int offset[] = new int[_rows.length];
      Vec anyVec = _fr.anyVec();
      for (int row=0; row<_rows.length; row++) {
        cidx[row] = anyVec.elem2ChunkIdx(_rows[row]);  // binary search of espc array.  TODO: sort input row numbers to avoid
        offset[row] = (int)(_rows[row] - anyVec.espc()[cidx[row]]);
      }
      Chunk c[] = new Chunk[anyVec.nChunks()];
      for (int col=0; col<_fr.numCols(); col++) {
        Vec v = _fr.vec(col);
        for (int i=0; i<c.length; i++) c[i] = v.chunkKey(i).home() ? v.chunkForChunkIdx(i) : null;
        for (int row=0; row<_rows.length; row++) {
          _chk[col][row] = c[cidx[row]].atd(offset[row]);
        }
      }

      // tell remote node to fill up Chunk[/*batch*/][/*rows*/]
      // perNodeRows[node] has perNodeRows[node].length batches of row numbers to fetch
      _rows=null;
      assert(_chk !=null);

      timeTaken = (System.nanoTime() - t0) / 1e9;

      tryComplete();
    }
  }
}
