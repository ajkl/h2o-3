from future import standard_library
standard_library.install_aliases()
from builtins import object
from ..frame import H2OFrame
import urllib.request, urllib.parse, urllib.error
from h2o import expr

class TransformAttributeError(AttributeError):
  def __init__(self,obj,method):
    super(AttributeError, self).__init__("No {} method for {}".format(method,obj.__class__.__name__))


class H2OTransformer(object):
  """H2O Transforms

  H2O Transforms implement the following methods
    * fit
    * transform
    * fit_transform
    * inverse_transform
    * export
  """
  # def __init__(self):
  #   self.parms=None

  def fit(self,X,y=None,**params):               raise TransformAttributeError(self,"fit")
  def transform(self,X,y=None,**params):         raise TransformAttributeError(self,"transform")
  def inverse_transform(self,X,y=None,**params): raise TransformAttributeError(self,"inverse_transform")
  def export(self,X,y,**params):                 raise TransformAttributeError(self,"export")
  def fit_transform(self, X, y=None, **params):
      return self.fit(X, y, **params).transform(X, **params)

  def get_params(self, deep=True):
    """
    Get parameters for this estimator.

    :param deep: (Optional) boolean; if True, return parameters of all subobjects that are estimators.
    :return: A dict of parameters.
    """
    out = dict()
    for key,value in self.parms.items():
      if deep and isinstance(value, H2OTransformer):
        deep_items = list(value.get_params().items())
        out.update((key + '__' + k, val) for k, val in deep_items)
      out[key] = value
    return out

  def set_params(self, **params):
    self.parms.update(params)
    return self

  @staticmethod
  def _dummy_frame():
    fr = H2OFrame._expr(expr.ExprNode())
    fr._ex._children = None
    fr._ex._cache.dummy_fill()
    return fr

  def to_rest(self, args):
    return urllib.parse.quote("{}__{}__{}__{}__{}".format(*args))